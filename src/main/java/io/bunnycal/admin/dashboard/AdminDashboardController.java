package io.bunnycal.admin.dashboard;

import io.bunnycal.admin.dashboard.dto.DashboardMetricsDto;
import io.bunnycal.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin dashboard metrics. Read-only; any admin role may view. A full growth time-series
 * arrives with the Revenue/Analytics phases — {@code metrics} already exposes 30-day
 * windowed figures and new-user counts for the initial dashboard.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'SUPPORT', 'FINANCE', 'OPERATIONS')")
public class AdminDashboardController {

    private final DashboardMetricsService metricsService;

    public AdminDashboardController(DashboardMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public ApiResponse<DashboardMetricsDto> metrics() {
        return ApiResponse.success(metricsService.metrics());
    }
}
