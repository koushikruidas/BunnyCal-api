package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Booking-time safety net for legacy event types that may predate orchestration validation.
 * Prevents partial-success flows where booking confirms but projection cannot provision
 * native conferencing for the selected account/provider capability.
 */
@Component
public class BookingConferencingCapabilityGuard {

    private final EventTypeRepository eventTypeRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;

    public BookingConferencingCapabilityGuard(EventTypeRepository eventTypeRepository,
                                              CalendarConnectionRepository calendarConnectionRepository) {
        this.eventTypeRepository = eventTypeRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
    }

    public void assertBookingConfirmationSupported(UUID bookingId, UUID hostId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findById(eventTypeId).orElse(null);
        if (eventType == null) {
            return;
        }
        ConferencingProviderType conferencingProvider = eventType.getConferencingProvider();
        if (conferencingProvider == null
                || conferencingProvider == ConferencingProviderType.NONE
                || conferencingProvider == ConferencingProviderType.CUSTOM_URL
                || conferencingProvider == ConferencingProviderType.ZOOM) {
            return;
        }

        // ROUND_ROBIN: conferencing link is created from the assigned participant's
        // calendar at booking time — no owner-level projection exists. Skip the
        // owner-projection provider checks entirely; the MSA check is not needed
        // here either because participant capability was validated at setup time.
        if (eventType.getKind() == EventKind.ROUND_ROBIN) {
            return;
        }

        CalendarProviderType projectionProvider = eventType.getProjectionProvider();
        if (conferencingProvider == ConferencingProviderType.GOOGLE_MEET
                && projectionProvider != CalendarProviderType.GOOGLE) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "Google Meet conferencing requires a Google projection calendar.");
        }
        if (conferencingProvider == ConferencingProviderType.MICROSOFT_TEAMS
                && projectionProvider != CalendarProviderType.MICROSOFT) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "Microsoft Teams conferencing requires a Microsoft projection calendar.");
        }
        CalendarConnection projectionConnection;
        if (eventType.getProjectionConnectionId() != null) {
            projectionConnection = calendarConnectionRepository.findById(eventType.getProjectionConnectionId()).orElse(null);
        } else {
            return;
        }
        if (projectionConnection == null) {
            return;
        }
        if (MicrosoftAccountClassifier.isConsumerMsa(projectionConnection)) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "Microsoft Teams conferencing requires a Microsoft 365 work/school account. "
                            + "Personal Outlook.com accounts are not supported for native Teams meeting provisioning.");
        }
    }
}
