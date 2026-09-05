package com.jobradar.core.service;

import com.jobradar.core.domain.Application;
import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.domain.Job;
import com.jobradar.core.domain.RecommendationStatus;
import com.jobradar.core.domain.WeeklyGoal;
import com.jobradar.core.dto.DashboardDtos.CalendarEvent;
import com.jobradar.core.dto.DashboardDtos.CalendarResponse;
import com.jobradar.core.dto.DashboardDtos.DashboardSummary;
import com.jobradar.core.dto.DashboardDtos.DeadlineItem;
import com.jobradar.core.dto.DashboardDtos.NextActionItem;
import com.jobradar.core.dto.DashboardDtos.ThisWeek;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.repository.ApplicationRepository;
import com.jobradar.core.repository.ApplicationEventRepository;
import com.jobradar.core.repository.JobRepository;
import com.jobradar.core.repository.RecommendationRepository;
import com.jobradar.core.repository.WeeklyGoalRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 聚合服务。聚合查询全部只读，一个 readOnly 事务包住多次查询，
 * 保证视图一致性（不会读到"漏斗加完了但新投递刚插入一半"的中间态）。
 */
@Service
public class DashboardService {

    private static final int UPCOMING_LIMIT = 10;

    private final ApplicationRepository applicationRepository;
    private final ApplicationEventRepository eventRepository;
    private final JobRepository jobRepository;
    private final RecommendationRepository recommendationRepository;
    private final WeeklyGoalRepository weeklyGoalRepository;

    public DashboardService(ApplicationRepository applicationRepository,
                            ApplicationEventRepository eventRepository,
                            JobRepository jobRepository,
                            RecommendationRepository recommendationRepository,
                            WeeklyGoalRepository weeklyGoalRepository) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
        this.jobRepository = jobRepository;
        this.recommendationRepository = recommendationRepository;
        this.weeklyGoalRepository = weeklyGoalRepository;
    }

    /** GET /dashboard/summary：漏斗 + 本周进展 + DDL 倒计时 + 待办（契约 §2.4） */
    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        // 漏斗：查一次 group by，再按契约补齐 0 值阶段
        Map<String, Long> funnel = new LinkedHashMap<>();
        for (ApplicationStage s : ApplicationStage.values()) {
            funnel.put(s.name().toLowerCase(java.util.Locale.ROOT), 0L);
        }
        for (ApplicationRepository.StageCount row : applicationRepository.countGroupByStage()) {
            funnel.put(row.getStage().name().toLowerCase(java.util.Locale.ROOT), row.getCnt());
        }

        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        Instant weekStart = monday.atStartOfDay(ZoneId.systemDefault()).toInstant();
        int goal = weeklyGoalRepository.findById(monday).map(WeeklyGoal::getTargetCount).orElse(10);
        ThisWeek thisWeek = new ThisWeek(
                eventRepository.countByToStageAndCreatedAtGreaterThanEqual(ApplicationStage.APPLIED, weekStart),
                goal,
                jobRepository.countByCreatedAtGreaterThanEqual(weekStart),
                recommendationRepository.countByStatus(RecommendationStatus.PENDING));

        List<DeadlineItem> deadlines = jobRepository
                .findByActiveTrueAndDeadlineGreaterThanEqualOrderByDeadlineAsc(
                        today, PageRequest.of(0, UPCOMING_LIMIT))
                .stream()
                .map(j -> new DeadlineItem(j.getId(), j.getCompany().getName(), j.getTitle(),
                        j.getDeadline(), ChronoUnit.DAYS.between(today, j.getDeadline())))
                .toList();

        List<NextActionItem> nextActions = applicationRepository
                .findByJobActiveTrueAndNextActionAtNotNullOrderByNextActionAt().stream()
                .limit(UPCOMING_LIMIT)
                .map(a -> new NextActionItem(a.getId(), a.getJob().getCompany().getName(),
                        a.getNextAction(), a.getNextActionAt()))
                .toList();

        return new DashboardSummary(funnel, thisWeek, deadlines, nextActions);
    }

    /**
     * GET /dashboard/calendar?month=YYYY-MM（契约 §2.5）。
     * W1 数据来源简化：deadline 取岗位截止日；planned 取计划投递日；
     * next_action 按当前 stage 细分类型（written_test/interview 阶段的待办即笔试/面试安排）。
     * 独立的笔面试日程表在数据模型中并不存在，W3 若需要再迭代。
     */
    @Transactional(readOnly = true)
    public CalendarResponse calendar(String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (Exception e) {
            throw new BadRequestException("month 格式应为 YYYY-MM: " + month);
        }
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        ZoneId zone = ZoneId.systemDefault();
        Instant monthStart = first.atStartOfDay(zone).toInstant();
        Instant monthEnd = last.plusDays(1).atStartOfDay(zone).toInstant();

        List<CalendarEvent> events = new ArrayList<>();

        jobRepository.findByActiveTrueAndDeadlineBetween(first, last)
                .forEach(j -> events.add(new CalendarEvent(j.getDeadline(), "deadline",
                        j.getCompany().getName() + " " + j.getTitle() + " 投递截止", j.getId(), null)));

        applicationRepository.findByJobActiveTrueAndPlannedAtBetween(first, last)
                .forEach(a -> events.add(new CalendarEvent(a.getPlannedAt(), "planned",
                        "计划投递：" + a.getJob().getCompany().getName(), a.getJob().getId(), a.getId())));

        for (Application a : applicationRepository.findByJobActiveTrueAndNextActionAtBetween(monthStart, monthEnd)) {
            Job j = a.getJob();
            String type = switch (a.getStage()) {
                case WRITTEN_TEST -> "written_test";
                case INTERVIEW -> "interview";
                default -> "next_action";
            };
            events.add(new CalendarEvent(a.getNextActionAt().atZone(zone).toLocalDate(), type,
                    j.getCompany().getName() + " " + nullToEmpty(a.getNextAction()),
                    j.getId(), a.getId()));
        }
        events.sort(Comparator.comparing(CalendarEvent::date));
        return new CalendarResponse(events);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
