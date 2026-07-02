package io.bunnycal.admin.revenue;

import io.bunnycal.admin.revenue.dto.RevenueReportDto;
import io.bunnycal.common.api.ApiResponse;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Revenue report. Single endpoint returns the full report (waterfall + by-plan +
 * over-time) for a window; default window is the last 30 days. FINANCE owns this surface.
 */
@RestController
@RequestMapping("/api/admin/revenue")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'FINANCE')")
public class AdminRevenueController {

    private final RevenueReportService service;

    public AdminRevenueController(RevenueReportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<RevenueReportDto> report(
            @RequestParam(value = "from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.success(service.report(from, to));
    }
}
