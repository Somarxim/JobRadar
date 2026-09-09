package com.jobradar.app.web;

import com.jobradar.core.dto.DashboardDtos.CalendarResponse;
import com.jobradar.core.dto.DashboardDtos.DashboardSummary;
import com.jobradar.core.dto.DashboardDtos.WeeklyReportView;
import com.jobradar.core.service.DashboardService;
import com.jobradar.core.service.WeeklyReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard API（/api/dashboard）：漏斗汇总、日历与周报 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final WeeklyReportService weeklyReportService;

    public DashboardController(DashboardService dashboardService, WeeklyReportService weeklyReportService) {
        this.dashboardService = dashboardService;
        this.weeklyReportService = weeklyReportService;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary();
    }

    @GetMapping("/calendar")
    public CalendarResponse calendar(@RequestParam String month) {
        return dashboardService.calendar(month);
    }

    /**
     * 周报（W4-2）：week_offset 0=本周，负数回看历史周；
     * narrative=true 时附带 LLM 叙事复盘（失败自动降级纯数据版，llm_generated=false）。
     */
    @GetMapping("/weekly-report")
    public WeeklyReportView weeklyReport(
            @RequestParam(name = "week_offset", defaultValue = "0") int weekOffset,
            @RequestParam(defaultValue = "false") boolean narrative) {
        return weeklyReportService.report(weekOffset, narrative);
    }
}
