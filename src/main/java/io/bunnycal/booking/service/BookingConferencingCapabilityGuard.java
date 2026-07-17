package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import io.bunnycal.conferencing.service.NativeConferencingCapabilityService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Last line of defence before a booking is confirmed: refuse to promise a meeting link the writer's
 * calendar cannot actually mint.
 *
 * <p>Under the global model this should never fire. An event type stores either the pointer
 * {@code DEFAULT} — resolved against the writer's <em>current</em> write-back calendar, so it cannot
 * disagree with it — or a provider-independent override that needs no particular calendar. The
 * settings page additionally refuses to leave a user's default meeting link and write-back calendar
 * inconsistent.
 *
 * <p>It is kept because the alternative failure is silent and lands on a guest: Graph accepts
 * {@code isOnlineMeeting=true} on a personal Outlook.com account and simply returns an event with no
 * join URL. Better to fail the confirmation loudly than to send someone a meeting they cannot join.
 */
@Component
public class BookingConferencingCapabilityGuard {

    private final EventTypeRepository eventTypeRepository;
    private final BookingSchedulingProjectionResolver projectionResolver;
    private final EventConferencingResolver conferencingResolver;
    private final NativeConferencingCapabilityService capabilityService;

    public BookingConferencingCapabilityGuard(EventTypeRepository eventTypeRepository,
                                              BookingSchedulingProjectionResolver projectionResolver,
                                              EventConferencingResolver conferencingResolver,
                                              NativeConferencingCapabilityService capabilityService) {
        this.eventTypeRepository = eventTypeRepository;
        this.projectionResolver = projectionResolver;
        this.conferencingResolver = conferencingResolver;
        this.capabilityService = capabilityService;
    }

    /**
     * @param hostId whoever receives the calendar event and therefore mints the link — the owner for
     *               1:1/group/collective, the assigned member for round-robin.
     */
    public void assertBookingConfirmationSupported(UUID bookingId, UUID hostId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findById(eventTypeId).orElse(null);
        if (eventType == null) {
            return;
        }
        ConferencingProviderType conferencing = conferencingResolver.resolve(hostId, eventType);
        if (conferencing == null || !conferencing.requiresCalendarProvider()) {
            return;   // Zoom, custom link, none: no calendar provider required.
        }

        CalendarConnection writeback = projectionResolver.writebackConnection(hostId).orElse(null);
        if (writeback == null) {
            // No write-back calendar at all, so nothing can mint the link. The resolver would already
            // have degraded to NONE, so reaching here means it resolved a native provider without one.
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    describe(conferencing) + " needs a connected calendar to create the meeting link, "
                            + "and this host has none.");
        }
        if (!capabilityService.canServe(writeback, conferencing)) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    describe(conferencing) + " cannot be created on this host's "
                            + writeback.getProvider() + " calendar.");
        }
    }

    private static String describe(ConferencingProviderType provider) {
        return provider == ConferencingProviderType.GOOGLE_MEET ? "Google Meet" : "Microsoft Teams";
    }
}
