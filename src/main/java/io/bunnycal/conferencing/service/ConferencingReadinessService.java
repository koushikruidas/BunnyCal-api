package io.bunnycal.conferencing.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.domain.ConferencingConnectionStatus;
import io.bunnycal.conferencing.repository.ZoomConferencingConnectionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Whether a given host can actually mint the meeting link an event type calls for.
 *
 * <p>Exists for round-robin assignment, which picks <em>which</em> host receives the booking and so
 * decides whose account has to produce the link. The rest of the app resolves conferencing against a
 * host that is already fixed; here the host is still being chosen, and choosing one who cannot mint
 * a link produces a confirmed booking whose join URL never materialises — the guest gets an
 * invitation with nothing to click.
 */
@Service
public class ConferencingReadinessService {

    private final ZoomConferencingConnectionRepository zoomConnectionRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final NativeConferencingCapabilityService capabilityService;
    private final EventConferencingResolver conferencingResolver;

    public ConferencingReadinessService(ZoomConferencingConnectionRepository zoomConnectionRepository,
                                        CalendarConnectionRepository calendarConnectionRepository,
                                        NativeConferencingCapabilityService capabilityService,
                                        EventConferencingResolver conferencingResolver) {
        this.zoomConnectionRepository = zoomConnectionRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.capabilityService = capabilityService;
        this.conferencingResolver = conferencingResolver;
    }

    /**
     * Can {@code hostId} produce the meeting link {@code eventType} requires?
     *
     * <p>Returns {@code true} whenever the event needs no provider-minted link — NONE and a custom
     * URL are satisfied by any host — so this only narrows the candidate pool for events that
     * genuinely depend on a per-host integration.
     */
    public boolean canProvideMeetingLink(UUID hostId, EventType eventType) {
        return canProvideMeetingLink(hostId, conferencingResolver.resolve(hostId, eventType));
    }

    /**
     * Same question against a provider the caller already resolved, for the create wizard: it must
     * compare coverage across the selectable options before any event type exists to resolve
     * against. Pass the stored/candidate provider — the pointer {@code DEFAULT} included, which
     * resolves per host exactly as it would on a real event.
     */
    public boolean canProvideMeetingLink(UUID hostId, ConferencingProviderType storedProvider) {
        ConferencingProviderType provider = conferencingResolver.resolve(hostId, storedProvider);
        if (provider == null
                || provider == ConferencingProviderType.NONE
                || provider == ConferencingProviderType.CUSTOM_URL) {
            return true;
        }
        if (provider == ConferencingProviderType.ZOOM) {
            // Zoom is a standalone per-user OAuth connection, unrelated to the calendar. A member
            // who never connected Zoom — or whose grant was revoked — cannot create the meeting.
            return zoomConnectionRepository.findByUserId(hostId)
                    .filter(connection -> connection.getStatus() != ConferencingConnectionStatus.REVOKED)
                    .isPresent();
        }
        if (!provider.requiresCalendarProvider()) {
            return true;
        }
        // Google Meet / Teams are minted by the write-back calendar, so readiness is that
        // connection's capability — the same check the confirmation guard applies.
        CalendarConnection writeback = calendarConnectionRepository
                .findByUserIdAndDefaultWritebackTrue(hostId)
                .orElse(null);
        return writeback != null && capabilityService.canServe(writeback, provider);
    }
}
