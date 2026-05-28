package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventType;
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
        EventType eventType = eventTypeRepository.findByIdAndUserId(eventTypeId, hostId).orElse(null);
        if (eventType == null) {
            return;
        }
        if (eventType.getProjectionProvider() != CalendarProviderType.MICROSOFT) {
            return;
        }
        if (eventType.getConferencingProvider() != ConferencingProviderType.MICROSOFT_TEAMS) {
            return;
        }
        if (eventType.getProjectionConnectionId() == null) {
            return;
        }
        CalendarConnection projectionConnection =
                calendarConnectionRepository.findById(eventType.getProjectionConnectionId()).orElse(null);
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

