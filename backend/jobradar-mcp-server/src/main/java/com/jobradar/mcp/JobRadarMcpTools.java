package com.jobradar.mcp;

import com.jobradar.core.domain.Application;
import com.jobradar.core.domain.ApplicationStage;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCard;
import com.jobradar.core.dto.ApplicationDtos.ApplicationCreateRequest;
import com.jobradar.core.dto.ApplicationDtos.ApplicationPatchRequest;
import com.jobradar.core.dto.ApplicationDtos.StageTransitionRequest;
import com.jobradar.core.dto.ApplicationDtos.TransitionResponse;
import com.jobradar.core.dto.DashboardDtos.DashboardSummary;
import com.jobradar.core.dto.DashboardDtos.DeadlineItem;
import com.jobradar.core.dto.DashboardDtos.NextActionItem;
import com.jobradar.core.dto.DashboardDtos.WeeklyEventItem;
import com.jobradar.core.dto.DashboardDtos.WeeklyReportView;
import com.jobradar.core.dto.JobDtos.IngestRequest;
import com.jobradar.core.dto.JobDtos.IngestResponse;
import com.jobradar.core.dto.JobDtos.JobDetail;
import com.jobradar.core.dto.JobDtos.JobSummary;
import com.jobradar.core.dto.MatchDtos.MatchReportView;
import com.jobradar.core.dto.PageResponse;
import com.jobradar.core.dto.RecommendDtos.RecommendationView;
import com.jobradar.core.exception.BadRequestException;
import com.jobradar.core.exception.ConflictException;
import com.jobradar.core.exception.NotFoundException;
import com.jobradar.core.repository.ApplicationRepository;
import com.jobradar.core.service.ApplicationService;
import com.jobradar.core.service.DashboardService;
import com.jobradar.core.service.JobService;
import com.jobradar.core.service.MatchingService;
import com.jobradar.core.service.RecommendService;
import com.jobradar.core.service.WeeklyReportService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * JobRadar MCP Tools（W4-1）：把 core service 薄封装成 Claude Desktop 可调用的工具。
 *
 * <p>设计原则（agent-design.md §6）：
 * <ul>
 *   <li>薄封装——业务逻辑全在 core service，这里只做参数解析、幂等合成、异常翻译；</li>
 *   <li>写操作幂等友好：重复调用返回当前状态而非报错（apply 已投递/update_stage 同阶段）；</li>
 *   <li>不猜不写：写工具只认 jobId（LLM 先 search_jobs 定位），定位不到唯一记录就返回错误说明；</li>
 *   <li>领域异常（404/409/400）翻译为 {ok:false, message} 结果对象而不是抛异常——
 *       让 LLM 读到中文原因后自己决定追问或换参，而不是把协议层错误甩给宿主。</li>
 * </ul>
 */
@Component
public class JobRadarMcpTools {

    private final JobService jobService;
    private final ApplicationService applicationService;
    private final DashboardService dashboardService;
    private final RecommendService recommendService;
    private final MatchingService matchingService;
    private final WeeklyReportService weeklyReportService;
    private final ApplicationRepository applicationRepository;

    public JobRadarMcpTools(JobService jobService,
                            ApplicationService applicationService,
                            DashboardService dashboardService,
                            RecommendService recommendService,
                            MatchingService matchingService,
                            WeeklyReportService weeklyReportService,
                            ApplicationRepository applicationRepository) {
        this.jobService = jobService;
        this.applicationService = applicationService;
        this.dashboardService = dashboardService;
        this.recommendService = recommendService;
        this.matchingService = matchingService;
        this.weeklyReportService = weeklyReportService;
        this.applicationRepository = applicationRepository;
    }

    /** 写操作统一返回：ok=false 时 message 是中文原因（LLM 可直接转述/追问） */
    public record ToolOutcome(boolean ok, String message, Object data) {
        static ToolOutcome ok(String message, Object data) {
            return new ToolOutcome(true, message, data);
        }

        static ToolOutcome fail(String message) {
            return new ToolOutcome(false, message, null);
        }
    }

    @Tool(name = "search_jobs", description = "搜索岗位库。query 匹配公司/岗位/城市；company_type 可选 "
            + "institute_military(军工所)/operator(运营商)/bank(银行)/soe_other(其他国企)/private(民企)；"
            + "stage 按投递阶段过滤（collected/planned/applied/written_test/interview/offer/rejected/withdrawn/none=未收藏）。返回岗位摘要列表。")
    public List<JobSummary> searchJobs(
            @ToolParam(required = false, description = "关键词，匹配公司名/岗位名/城市") String query,
            @ToolParam(required = false, description = "公司类型，如 institute_military/bank") String companyType,
            @ToolParam(required = false, description = "城市，如 北京") String city,
            @ToolParam(required = false, description = "投递阶段，如 applied；none 表示未收藏") String stage,
            @ToolParam(required = false, description = "返回条数上限，默认 10，最大 50") Integer limit) {
        int size = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
        PageResponse<JobSummary> page = jobService.search(query, companyType, city, stage,
                null, null, "created_desc", 1, size);
        return page.items();
    }

    @Tool(name = "get_job_detail", description = "查看岗位完整详情：JD 全文、结构化要求、投递状态、最新匹配报告。")
    public JobDetail getJobDetail(@ToolParam(description = "岗位 ID（search_jobs 结果里的 id）") long jobId) {
        return jobService.detail(jobId);
    }

    @Tool(name = "add_job", description = "粘贴 JD 原文录入岗位（AI 自动解析公司/岗位/城市/截止日）。"
            + "同一公司+岗位+城市重复录入时返回已存在的岗位（already_exists=true），不会产生重复数据。")
    public ToolOutcome addJob(
            @ToolParam(description = "JD 原文（从网页复制的招聘描述全文）") String rawText,
            @ToolParam(required = false, description = "岗位页面链接") String url) {
        try {
            IngestResponse res = jobService.ingest(new IngestRequest("mcp", url, rawText, null, null, null, null));
            return ToolOutcome.ok(
                    res.alreadyExists() ? "该岗位已存在，返回已有记录" : "录入成功，岗位 ID=" + res.jobId(), res);
        } catch (BadRequestException | NotFoundException e) {
            return ToolOutcome.fail("录入失败：" + e.getMessage());
        }
    }

    @Tool(name = "apply", description = "记录一次岗位投递（状态置为已投递）。channel 必填：official(官网)/boss/niuke/email/referral(内推)/campus_talk(宣讲会)。"
            + "未收藏过的岗位会自动建卡；已投递的重复调用返回当前状态（幂等）。")
    public ToolOutcome apply(
            @ToolParam(description = "岗位 ID") long jobId,
            @ToolParam(description = "投递渠道：official/boss/niuke/email/referral/campus_talk") String channel,
            @ToolParam(required = false, description = "备注，如投递版本、内推人") String note,
            @ToolParam(required = false, description = "投递时间 ISO 8601，补录用；缺省为现在") String appliedAt) {
        try {
            Application app = applicationRepository.findByJobId(jobId).orElse(null);
            if (app == null) {
                // 未收藏：先建卡再流转（自动待办会随之更新到"跟进进度，准备笔试"）
                ApplicationCard card = applicationService.create(new ApplicationCreateRequest(jobId, null, null, null, null));
                app = applicationRepository.findById(card.id()).orElseThrow();
            }
            if (app.getStage() == ApplicationStage.APPLIED) {
                return ToolOutcome.ok("该岗位已是已投递状态（幂等返回），渠道=" + app.getChannel(), app.getId());
            }
            TransitionResponse res = applicationService.transition(app.getId(),
                    new StageTransitionRequest(ApplicationStage.APPLIED, note, channel, appliedAt));
            return ToolOutcome.ok("已记录投递：" + res.application().companyName() + " · " + res.application().title()
                    + "（渠道 " + res.application().channel() + "）", res.application());
        } catch (NotFoundException | BadRequestException | ConflictException e) {
            return ToolOutcome.fail(e.getMessage());
        }
    }

    @Tool(name = "update_stage", description = "更新投递阶段。to_stage: collected/planned/applied/written_test/interview/offer/rejected/withdrawn。"
            + "流转到已投递必须带 channel；重复流转到同一阶段幂等返回。系统自动维护对应的下一步待办。")
    public ToolOutcome updateStage(
            @ToolParam(description = "岗位 ID") long jobId,
            @ToolParam(description = "目标阶段，如 interview") String toStage,
            @ToolParam(required = false, description = "备注") String note,
            @ToolParam(required = false, description = "渠道（仅 to_stage=applied 时需要）") String channel) {
        ApplicationStage stage;
        try {
            stage = ApplicationStage.valueOf(toStage.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ToolOutcome.fail("无效阶段：" + toStage + "。可选：collected/planned/applied/written_test/interview/offer/rejected/withdrawn");
        }
        try {
            Application app = applicationRepository.findByJobId(jobId)
                    .orElseThrow(() -> new NotFoundException("该岗位还没有投递记录（先用 apply 或收藏）: jobId=" + jobId));
            if (app.getStage() == stage) {
                return ToolOutcome.ok("已是 " + stage.name().toLowerCase(Locale.ROOT) + " 阶段（幂等返回）", app.getId());
            }
            TransitionResponse res = applicationService.transition(app.getId(),
                    new StageTransitionRequest(stage, note, channel, null));
            return ToolOutcome.ok("已更新：" + res.application().companyName() + " → "
                    + stage.name().toLowerCase(Locale.ROOT), res.application());
        } catch (NotFoundException | BadRequestException e) {
            return ToolOutcome.fail(e.getMessage());
        }
    }

    @Tool(name = "set_next_action", description = "为某岗位的投递设置自定义下一步待办（覆盖系统按阶段生成的默认待办），如『周五前完成移动笔试』。")
    public ToolOutcome setNextAction(
            @ToolParam(description = "岗位 ID") long jobId,
            @ToolParam(description = "待办内容，如 完成移动笔试") String action,
            @ToolParam(required = false, description = "时间 ISO 8601，如 2026-09-12T18:00:00+08:00；不填则显示为『尽快』") String at) {
        try {
            Application app = applicationRepository.findByJobId(jobId)
                    .orElseThrow(() -> new NotFoundException("该岗位还没有投递记录: jobId=" + jobId));
            Instant when = parseInstant(at);
            applicationService.patch(app.getId(), new ApplicationPatchRequest(null, null, action, when, null, null));
            return ToolOutcome.ok("已设置待办：" + action + (when == null ? "（尽快）" : "（" + at + "）"), app.getId());
        } catch (NotFoundException e) {
            return ToolOutcome.fail(e.getMessage());
        }
    }

    @Tool(name = "today", description = "今天该干什么：待办事项（含系统按阶段自动生成的）+ 临近截止的岗位。")
    public TodayView today() {
        DashboardSummary s = dashboardService.summary();
        List<DeadlineItem> urgent = s.upcomingDeadlines().stream()
                .filter(d -> d.daysLeft() <= 3)
                .toList();
        StringBuilder md = new StringBuilder("## 今日概览\n");
        md.append("- 待办 ").append(s.nextActions().size()).append(" 项；3 天内截止岗位 ").append(urgent.size()).append(" 个\n");
        if (!s.nextActions().isEmpty()) {
            md.append("\n### 待办\n");
            for (NextActionItem a : s.nextActions()) {
                md.append("- ").append(a.company()).append("：").append(a.nextAction())
                        .append(a.nextActionAt() == null ? "（尽快）" : "（" + a.nextActionAt() + "）").append('\n');
            }
        }
        if (!urgent.isEmpty()) {
            md.append("\n### 临近截止\n");
            for (DeadlineItem d : urgent) {
                md.append("- ").append(d.company()).append(" · ").append(d.title())
                        .append("：剩 ").append(d.daysLeft()).append(" 天（").append(d.deadline()).append("）\n");
            }
        }
        return new TodayView(md.toString(), s.nextActions(), urgent);
    }

    public record TodayView(String summary, List<NextActionItem> nextActions, List<DeadlineItem> urgentDeadlines) {
    }

    @Tool(name = "weekly_report", description = "投递复盘周报：投递数 vs 目标 vs 上周环比、阶段流转、事件流水、推荐采纳率，"
            + "并附 AI 叙事复盘（LLM 不可用时自动降级纯数据版）。week_offset: 0=本周，-1=上周，以此类推。")
    public ToolOutcome weeklyReport(
            @ToolParam(required = false, description = "0=本周（默认），-1=上周，-2=上上周……") Integer weekOffset) {
        int offset = weekOffset == null ? 0 : weekOffset;
        try {
            WeeklyReportView r = weeklyReportService.report(offset, true);
            StringBuilder md = new StringBuilder("## 投递周报（" + r.weekStart() + " ~ " + r.weekEnd() + "）\n\n");
            md.append("- 本周投递：").append(r.stats().applied()).append(" / 目标 ").append(r.stats().goal())
                    .append("（上周 ").append(r.stats().prevWeekApplied()).append("）\n");
            md.append("- 新收录岗位：").append(r.stats().newJobs()).append('\n');
            md.append("- 推荐：生成 ").append(r.stats().recGenerated())
                    .append("，采纳 ").append(r.stats().recAccepted())
                    .append("，忽略 ").append(r.stats().recIgnored()).append('\n');
            if (!r.stats().stageInflow().isEmpty()) {
                md.append("- 阶段流转：");
                r.stats().stageInflow().forEach((s, c) -> md.append(s).append("×").append(c).append("  "));
                md.append('\n');
            }
            if (!r.events().isEmpty()) {
                md.append("\n### 事件流水\n");
                for (WeeklyEventItem e : r.events()) {
                    md.append("- ").append(e.company()).append(" · ").append(e.title())
                            .append(" → ").append(e.toStage()).append('\n');
                }
            }
            if (r.narrativeMarkdown() != null) {
                md.append("\n---\n\n").append(r.narrativeMarkdown()).append('\n');
            } else {
                md.append("\n（AI 叙事复盘暂不可用，以上为纯数据版）\n");
            }
            return ToolOutcome.ok("周报区间 " + r.weekStart() + " ~ " + r.weekEnd(), md.toString());
        } catch (BadRequestException e) {
            return ToolOutcome.fail(e.getMessage());
        }
    }

    @Tool(name = "match_job", description = "AI 匹配分析：岗位 JD vs 我的简历，输出匹配分、硬性条件核对与差距分析（调用 LLM 约 3-10 秒）。不传 resume_id 用默认简历。")
    public ToolOutcome matchJob(
            @ToolParam(description = "岗位 ID") long jobId,
            @ToolParam(required = false, description = "简历 ID，缺省用默认简历") Long resumeId) {
        try {
            MatchReportView report = matchingService.match(jobId, resumeId);
            return ToolOutcome.ok("匹配分 " + report.scoreTotal(), report);
        } catch (NotFoundException | BadRequestException e) {
            return ToolOutcome.fail(e.getMessage());
        }
    }

    @Tool(name = "recommend_today", description = "今日推荐 Top 5（每日 07:45 自动生成：先按求职方向初筛，再由 AI 逐个评估）。"
            + "为空说明今日推荐已全部处理或未生成。")
    public List<RecommendationView> recommendToday() {
        return recommendService.today();
    }

    /** ISO 宽容解析：带时区按 OffsetDateTime，不带按本地时区（与 ApplicationService 同一约定） */
    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException e) {
                throw new BadRequestException("时间无法解析为 ISO 8601: " + raw);
            }
        }
    }
}
