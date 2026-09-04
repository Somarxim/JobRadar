package com.jobradar.app.web;

import com.jobradar.core.dto.DashboardDtos.CalendarResponse;
import com.jobradar.core.dto.DashboardDtos.DashboardSummary;
import com.jobradar.core.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard API（/api/dashboard）：漏斗汇总与日历 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary();
    }

    @GetMapping("/calendar")
    public CalendarResponse calendar(@RequestParam String month) {
        return dashboardService.calendar(month);
    }
}
