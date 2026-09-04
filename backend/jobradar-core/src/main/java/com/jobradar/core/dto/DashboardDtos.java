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
}
