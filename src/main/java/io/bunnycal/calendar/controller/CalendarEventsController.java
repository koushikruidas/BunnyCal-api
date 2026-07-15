package io.bunnycal.calendar.controller;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.dto.CalendarEventDto;
import io.bunnycal.calendar.dto.CalendarEventsResponse;
import io.bunnycal.calendar.dto.CalendarHolidayDto;
import io.bunnycal.calendar.dto.CalendarHolidaysResponse;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.availability.service.HolidayDayOffService;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.api.ApiResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final HolidayDayOffService holidayDayOffService;
    private final UserRepository userRepository;

    public CalendarEventsController(CalendarEventRepository calendarEventRepository,
                                    CalendarConnectionRepository calendarConnectionRepository,
                                    HolidayDayOffService holidayDayOffService,
                                    UserRepository userRepository) {
        this.calendarEventRepository = calendarEventRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.holidayDayOffService = holidayDayOffService;
        this.userRepository = userRepository;
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<CalendarEventsResponse>> listEvents(
            Authentication authentication,
            @RequestParam Instant start,
            @RequestParam Instant end) {

        UUID userId = extractUserId(authentication);

        List<CalendarEvent> events =
                calendarEventRepository
                        .findDisplayEventsOnPrimaryCalendars(
                                userId, end, start);

        // Load all non-revoked connections so the join covers ACTIVE, SYNCING, FAILED, and
        // ERROR states — events already ingested from a SYNCING connection are valid and
        // should appear in the display with their source label.
        Map<UUID, CalendarConnection> connectionsById =
                calendarConnectionRepository.findByUserIdAndStatusNot(userId, CalendarConnectionStatus.REVOKED)
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
                            "busy",
                            e.getExternalEventId()
                    );
                })
                .toList();

        return ResponseEntity.ok(ApiResponse.success(new CalendarEventsResponse(dtos)));
    }

    /**
     * Backend-authoritative holiday list. Deduplication happens before dates are returned, so the
     * dashboard and slot engine cannot disagree about which neighbouring date survives.
     */
    @GetMapping("/holidays")
    public ResponseEntity<ApiResponse<CalendarHolidaysResponse>> listHolidays(
            Authentication authentication,
            @RequestParam LocalDate start,
            @RequestParam LocalDate end) {
        UUID userId = extractUserId(authentication);
        String timezone = userRepository.findById(userId).map(user -> user.getTimezone()).orElse("UTC");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (RuntimeException ignored) {
            zoneId = ZoneId.of("UTC");
        }

        List<CalendarHolidayDto> holidays = holidayDayOffService.holidays(userId, start, end, zoneId)
                .stream()
                .map(holiday -> new CalendarHolidayDto(
                        holiday.date() + ":" + Integer.toUnsignedString(
                                java.util.Objects.toString(holiday.title(), "Holiday").toLowerCase().hashCode(), 36),
                        holiday.title() == null || holiday.title().isBlank() ? "Holiday" : holiday.title().trim(),
                        holiday.date()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(new CalendarHolidaysResponse(holidays)));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("Authenticated user id is required");
        }
        return userId;
    }
}
