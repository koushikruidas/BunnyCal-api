package io.bunnycal.admin.analytics;

import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.CountriesReportDto;
import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.TopEventDto;
import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.AnalyticsSummaryDto;
import io.bunnycal.admin.analytics.dto.AdminAnalyticsDtos.TimezoneBreakdownDto;
import io.bunnycal.common.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/analytics")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OPERATIONS')")
public class AdminAnalyticsController {

    private final ProductAnalyticsService service;

    public AdminAnalyticsController(ProductAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<AnalyticsSummaryDto> summary(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.success(service.summary(from, to));
    }

    @GetMapping("/top-events")
    public ApiResponse<List<TopEventDto>> topEvents(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.success(service.topEvents(from, to));
    }

    @GetMapping("/timezones")
    public ApiResponse<List<TimezoneBreakdownDto>> timezones() {
        return ApiResponse.success(service.timezones());
    }

    @GetMapping("/countries")
    public ApiResponse<CountriesReportDto> countries() {
        return ApiResponse.success(service.countries());
    }
}
