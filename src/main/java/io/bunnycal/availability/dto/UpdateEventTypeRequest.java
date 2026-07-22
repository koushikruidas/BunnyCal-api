package io.bunnycal.availability.dto;

import io.bunnycal.availability.domain.GroupHostNotificationMode;
import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.bunnycal.hostpayments.dto.EventPaymentRequest;

/**
 * Partial update of an event type. Every field is optional: an absent (null) field leaves the stored
 * value alone, so a client can send only what it is changing.
 *
 * <p>{@code kind} is deliberately absent — it gates entitlements at creation and is immutable. There
 * are no calendar fields either: which calendars block you and which one receives your bookings are
 * account-level settings now, not per-event-type choices.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateEventTypeRequest(
        String name,
        String description,
        String location,
        Integer durationMinutes,
        Integer bufferBeforeMinutes,
        Integer bufferAfterMinutes,
        Integer slotIntervalMinutes,
        Integer minNoticeMinutes,
        Integer maxAdvanceDays,
        Integer holdDurationMinutes,
        CreateEventTypeRequest.ConferenceRequest conference,
        Integer capacity,
        GroupHostNotificationMode groupHostNotificationMode,
        EventPaymentRequest payment
) implements ForwardCompatibleRequest {
    /** Back-compatible constructor used before paid-event configuration was added. */
    public UpdateEventTypeRequest(
            String name, String description, String location, Integer durationMinutes,
            Integer bufferBeforeMinutes, Integer bufferAfterMinutes, Integer slotIntervalMinutes,
            Integer minNoticeMinutes, Integer maxAdvanceDays, Integer holdDurationMinutes,
            CreateEventTypeRequest.ConferenceRequest conference, Integer capacity,
            GroupHostNotificationMode groupHostNotificationMode) {
        this(name, description, location, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes,
                slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays, holdDurationMinutes,
                conference, capacity, groupHostNotificationMode, null);
    }

    /** Back-compatible constructor for existing partial-update callers. */
    public UpdateEventTypeRequest(
            String name,
            String description,
            String location,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            Integer slotIntervalMinutes,
            Integer minNoticeMinutes,
            Integer maxAdvanceDays,
            Integer holdDurationMinutes,
            CreateEventTypeRequest.ConferenceRequest conference
    ) {
        this(name, description, location, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes,
                slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays, holdDurationMinutes,
                conference, null, null, null);
    }
}
