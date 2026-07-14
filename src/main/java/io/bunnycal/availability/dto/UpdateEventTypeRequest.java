package io.bunnycal.availability.dto;

import io.bunnycal.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Partial update of an event type. Every field is optional: an absent (null) field leaves the stored
 * value alone, so a client can send only what it is changing.
 *
 * <p>{@code kind} is deliberately absent — it gates entitlements at creation and is immutable.
 *
 * <p>Note the one asymmetry: because null means "leave unchanged", clearing a pinned booking
 * calendar back to "use my default" cannot be expressed by sending null. Send an empty
 * {@link CreateEventTypeRequest.ProjectionDestinationRequest} (all fields null/blank) to unpin it —
 * the same shape the create path already reads as "no destination chosen". Likewise an empty
 * {@code availabilityCalendars} list means "no explicit selection" (ALL_CONNECTED), not "unchanged".
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
        List<CreateEventTypeRequest.AvailabilityCalendarRequest> availabilityCalendars,
        CreateEventTypeRequest.ConferenceRequest conference,
        CreateEventTypeRequest.ProjectionDestinationRequest projectionDestination
) implements ForwardCompatibleRequest {
}
