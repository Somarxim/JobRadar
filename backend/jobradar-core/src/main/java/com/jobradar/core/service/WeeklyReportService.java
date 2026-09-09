package com.jobradar.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.core.domain.ApplicationEvent;
import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.domain.RecommendationStatus;
import com.jobradar.core.domain.WeeklyGoal;
import com.jobradar.core.dto.DashboardDtos.WeeklyEventItem;
import com.jobradar.core.dto.DashboardDtos.WeeklyReportView;
import com.jobradar.core.dto.DashboardDtos.WeeklyStats;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.llm.LlmService;
import com.jobradar.core.repository.ApplicationEventRepository;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.repository.RecommendationRepository;
import com.jobradar.core.repository.WeeklyGoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 周报 Agent（W4-2）：两层结构。
 * <ul>
 *   <li><b>数据层</b>（确定性 SQL 聚合，永远可用）：周区间内的流转事件流水、
 *       阶段流入计数、投递数 vs 目标 vs 上周环比、新收录岗位、推荐生成/采纳/忽略；</li>
 *   <li><b>叙事层</b>（LLM，可选）：把统计 JSON 交给模型写 Markdown 复盘，
 *       失败/未启用时 narrativeMarkdown=null、llmGenerated=false——
 *       调用方据此提示「纯数据版」，功能不中断。</li>
 * </ul>
 * 周口径：周一至周日（与 Dashboard 本周统计同一约定）；weekOffset 0=本周，负数回看历史周。
 */
@Service
public class WeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);

    /** 事件流水上限：防极端活跃周把 LLM 输入撑爆 */
    private static final int MAX_EVENTS = 50;

    /** 与 DashboardService 本周目标缺省值保持一致（未配置周目标时的兜底） */
    private static final int DEFAULT_WEEKLY_GOAL = 10;

    private final ApplicationEventRepository eventRepository;
    private final JobRepository jobRepository;
    private final RecommendationRepository recommendationRepository;
    private final WeeklyGoalRepository weeklyGoalRepository;
    private final LlmService llmService;
    /** 仅用于组装 LLM 输入 JSON，不走 API 契约——本地实例即可，不注入全局命名策略 */
    private final ObjectMapper promptMapper = new ObjectMapper();

    public WeeklyReportService(ApplicationEventRepository eventRepository,
                               JobRepository jobRepository,
                               RecommendationRepository recommendationRepository,
                               WeeklyGoalRepository weeklyGoalRepository,
                               LlmService llmService) {
        this.eventRepository = eventRepository;
        this.jobRepository = jobRepository;
        this.recommendationRepository = recommendationRepository;
        this.weeklyGoalRepository = weeklyGoalRepository;
        this.llmService = llmService;
    }

    @Transactional(readOnly = true)
    public WeeklyReportView report(int weekOffset, boolean narrative) {
        if (weekOffset > 0) {
            throw new BadRequestException("week_offset 不能为正，只能复盘本周或历史周: " + weekOffset);
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY).plusWeeks(weekOffset);
        LocalDate sunday = monday.plusDays(6);
        Instant from = monday.atStartOfDay(zone).toInstant();
        Instant toExclusive = sunday.plusDays(1).atStartOfDay(zone).toInstant();

        List<ApplicationEvent> weekEvents = eventRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, toExclusive);

        Map<String, Long> stageInflow = new LinkedHashMap<>();
        long applied = 0;
        for (ApplicationEvent e : weekEvents) {
            stageInflow.merge(e.getToStage().name().toLowerCase(Locale.ROOT), 1L, Long::sum);
            if (e.getToStage() == ApplicationStage.APPLIED) {
                applied++;
            }
        }

        // 环比基准：上一自然周的投递数（与本周同为整周区间，口径可比）
        long prevApplied = eventRepository.countByToStageAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                ApplicationStage.APPLIED, monday.minusWeeks(1).atStartOfDay(zone).toInstant(), from);

        int goal = weeklyGoalRepository.findById(monday)
                .map(WeeklyGoal::getTargetCount).orElse(DEFAULT_WEEKLY_GOAL);
        long newJobs = jobRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, toExclusive);
        long recGenerated = recommendationRepository.countByRecDateBetween(monday, sunday);
        long recAccepted = recommendationRepository.countByRecDateBetweenAndStatus(
                monday, sunday, RecommendationStatus.ACCEPTED);
        long recIgnored = recommendationRepository.countByRecDateBetweenAndStatus(
                monday, sunday, RecommendationStatus.IGNORED);

        List<WeeklyEventItem> items = weekEvents.stream().limit(MAX_EVENTS)
                .map(e -> new WeeklyEventItem(e.getCreatedAt(),
                        e.getApplication().getJob().getCompany().getName(),
                        e.getApplication().getJob().getTitle(),
                        e.getToStage().name().toLowerCase(Locale.ROOT),
                        e.getNote()))
                .toList();

        WeeklyStats stats = new WeeklyStats(applied, goal, prevApplied, newJobs,
                recGenerated, recAccepted, recIgnored, stageInflow);

        String narrativeMarkdown = null;
        if (narrative) {
            narrativeMarkdown = llmService.narrateWeeklyReport(toPromptJson(monday, sunday, stats, items))
                    .orElse(null);
        }
        return new WeeklyReportView(monday, sunday, stats, items,
                narrativeMarkdown, narrativeMarkdown != null);
    }

    /**
     * LLM 输入：只给统计数字与「公司+流向」事件摘要——不含 note 原文等自由文本，
     * 既省 token 也避免把用户私人备注不必要地发给模型。
     */
    private String toPromptJson(LocalDate monday, LocalDate sunday,
                                WeeklyStats stats, List<WeeklyEventItem> items) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", monday + " ~ " + sunday);
            data.put("applied", stats.applied());
            data.put("weekly_goal", stats.goal());
            data.put("prev_week_applied", stats.prevWeekApplied());
            data.put("new_jobs_collected", stats.newJobs());
            data.put("recommendations_generated", stats.recGenerated());
            data.put("recommendations_accepted", stats.recAccepted());
            data.put("recommendations_ignored", stats.recIgnored());
            data.put("stage_inflow", stats.stageInflow());
            data.put("events", items.stream()
                    .map(i -> Map.of("company", i.company(), "to", i.toStage()))
                    .toList());
            return promptMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            // 全是字符串/数字/Long，理论上不会失败；失败时降级为空对象让 LLM 明示无数据
            log.warn("周报统计 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}
