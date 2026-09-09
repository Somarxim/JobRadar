package com.jobradar.core.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Dashboard 相关 DTO。契约见 docs/api-design.md §2.4/2.5。 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record DashboardSummary(Map<String, Long> funnel,
                                   ThisWeek thisWeek,
                                   List<DeadlineItem> upcomingDeadlines,
                                   List<NextActionItem> nextActions) {
    }

    public record ThisWeek(long applied, int goal, long newJobs, long recommendationsUnread) {
    }

    public record DeadlineItem(Long jobId, String company, String title,
                               LocalDate deadline, long daysLeft) {
    }

    public record NextActionItem(Long applicationId, String company,
                                 String nextAction, java.time.Instant nextActionAt) {
    }

    public record CalendarResponse(List<CalendarEvent> events) {
    }

    /** type: deadline / written_test / interview / planned / next_action */
    public record CalendarEvent(LocalDate date, String type, String title,
                                Long jobId, Long applicationId) {
    }

    /**
     * 周报（W4-2）：weekStart~weekEnd 的复盘视图。narrative_markdown 为 LLM 叙事复盘，
     * LLM 未启用/调用失败时为 null 且 llm_generated=false（纯数据降级版）。
     */
    public record WeeklyReportView(LocalDate weekStart, LocalDate weekEnd,
                                   WeeklyStats stats, List<WeeklyEventItem> events,
                                   String narrativeMarkdown, boolean llmGenerated) {
    }

    /** stageInflow：本周各阶段流入计数（key 为小写阶段名） */
    public record WeeklyStats(long applied, int goal, long prevWeekApplied,
                              long newJobs, long recGenerated, long recAccepted, long recIgnored,
                              Map<String, Long> stageInflow) {
    }

    public record WeeklyEventItem(java.time.Instant at, String company, String title,
                                  String toStage, String note) {
    }

    /** 图表统计（W4-3）：daily 近 N 天逐日数据（含 0 值日，前端直接画）；company_types 在架岗位类型分布 */
    public record DashboardStats(List<DailyPoint> daily, List<TypeShare> companyTypes) {
    }

    public record DailyPoint(LocalDate date, long applied, long newJobs) {
    }

    /** type 为小写公司类型枚举（未分类归并到 other） */
    public record TypeShare(String type, long count) {
    }
}
