package io.bunnycal.embed.analytics.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.embed.analytics.service.WidgetAnalyticsService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/embed/analytics")
public class PublicWidgetAnalyticsController {

    private final WidgetAnalyticsService analyticsService;

    public PublicWidgetAnalyticsController(WidgetAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/session")
    public ResponseEntity<ApiResponse<Map<String, UUID>>> startSession(
            @RequestBody StartSessionRequest request) {
        UUID sessionId = UUID.randomUUID();
        analyticsService.startSession(
                sessionId,
                request.experienceId(),
                request.anonymousId() != null ? request.anonymousId() : UUID.randomUUID(),
                request.utmSource(),
                request.utmMedium(),
                request.utmCampaign(),
                request.referrer()
        );
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("sessionId", sessionId)));
    }

    @PostMapping("/session/{sessionId}/events")
    public ResponseEntity<ApiResponse<Void>> recordEvent(
            @PathVariable UUID sessionId,
            @RequestBody RecordEventRequest request) {
        analyticsService.recordEvent(sessionId, request.eventName());
        return ResponseEntity.accepted().body(ApiResponse.success(null));
    }

    public record StartSessionRequest(
            UUID experienceId,
            UUID anonymousId,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String referrer
    ) {}

    public record RecordEventRequest(String eventName) {}
}
