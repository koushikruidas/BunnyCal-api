package io.bunnycal.calendar.controller;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.dto.CalendarEventDto;
import io.bunnycal.calendar.dto.CalendarEventsResponse;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.common.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar")
public class CalendarEventsController {

    private final CalendarEventRepository calendarEventRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;

    public CalendarEventsController(CalendarEventRepository calendarEventRepository,
                                    CalendarConnectionRepository calendarConnectionRepository) {
        this.calendarEventRepository = calendarEventRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<CalendarEventsResponse>> listEvents(
            Authentication authentication,
            @RequestParam Instant start,
            @RequestParam Instant end) {

        UUID userId = extractUserId(authentication);

        List<CalendarEvent> events =
                calendarEventRepository
                        .findByUserIdAndCancelledFalseAndDeletedFalseAndStartsAtLessThanAndEndsAtGreaterThan(
                                userId, end, start);

        Map<UUID, CalendarConnection> connectionsById =
                calendarConnectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE)
                        .stream()
                        .collect(Collectors.toMap(CalendarConnection::getId, c -> c));

        List<CalendarEventDto> dtos = events.stream()
                .map(e -> {
                    CalendarConnection conn = connectionsById.get(e.getConnectionId());
                    String sourceName = conn != null ? conn.getProviderUserId() : null;
                    return new CalendarEventDto(
                            e.getId().toString(),
                            e.getTitle(),
                            e.getStartsAt(),
                            e.getEndsAt(),
                            e.getConnectionId().toString(),
                            sourceName,
                            e.getProvider(),
                            "busy"
                    );
                })
                .toList();

        return ResponseEntity.ok(ApiResponse.success(new CalendarEventsResponse(dtos)));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("Authenticated user id is required");
        }
        return userId;
    }
}
