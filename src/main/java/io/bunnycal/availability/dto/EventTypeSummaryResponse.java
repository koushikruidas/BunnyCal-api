package io.bunnycal.availability.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.bunnycal.availability.domain.EventAvailabilityMode;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.GroupHostNotificationMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EventTypeSummaryResponse(
        UUID id,
        String name,
        String slug,
        String link,
        EventKind kind,
        int capacity,
        GroupHostNotificationMode groupHostNotificationMode,
        int durationMinutes,
        boolean published,
        boolean degraded,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate seriesStartDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate seriesEndDate,
        ConferenceResponse conference,
        EventAvailabilityMode availabilityMode,
        /**
         * The event's own custom schedule windows, empty when it inherits defaults (or
         * when an explicit custom schedule is closed). Carried on the summary so the dashboard can render each
         * card's bookable window without a request per card.
         */
        List<AvailabilityWindowResponse> availabilityWindows,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String location,
        int bufferBeforeMinutes,
        int bufferAfterMinutes,
        int slotIntervalMinutes,
        int minNoticeMinutes,
        int maxAdvanceDays,
        int holdDurationMinutes
) {
    public EventTypeSummaryResponse(UUID id, String name, String slug, String link) {
        this(id, name, slug, link, EventKind.ONE_ON_ONE, 1, GroupHostNotificationMode.SMART_SUMMARY, 30, true, false,
                null, null, null, EventAvailabilityMode.INHERIT, List.of(),
                null, null, 0, 0, 30, 0, 30, 10);
    }

    public record AvailabilityWindowResponse(
            String dayOfWeek,
            String startTime,
            String endTime
    ) {}

    /**
     * {@code provider} is {@code DEFAULT} when the event follows the user's global default meeting
     * link (the usual case), or a provider-independent override: {@code ZOOM}, {@code CUSTOM_URL},
     * {@code NONE}. Never {@code GOOGLE_MEET} or {@code MICROSOFT_TEAMS} — those are only ever the
     * resolved value of {@code DEFAULT}, not something an event type can hold.
     */
    public record ConferenceResponse(
            boolean enabled,
            String provider,
            @JsonInclude(JsonInclude.Include.NON_NULL) String customUrl
    ) {}
}
