package io.bunnycal.conferencing.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves an event type's conferencing choice into the provider that will actually be used.
 *
 * <p>An event type stores one of two things:
 * <ul>
 *   <li>{@link ConferencingProviderType#DEFAULT} — a <em>pointer</em> meaning "use my global default
 *       meeting link". Resolved here, live, against the writer's current settings.</li>
 *   <li>A provider-independent <em>value</em> ({@code ZOOM}, {@code CUSTOM_URL}, {@code NONE}) —
 *       returned unchanged. None of these needs a particular calendar provider to produce a link, so
 *       none of them can be invalidated by the user later moving their write-back calendar.</li>
 * </ul>
 *
 * <p>{@code GOOGLE_MEET} and {@code MICROSOFT_TEAMS} are never stored on an event type; they exist
 * only as the resolved output of the pointer. That is what makes the write-back calendar safe to
 * change: a Meet link can only be minted on a Google calendar and a Teams link only on a Microsoft
 * one, so an event type that had frozen {@code GOOGLE_MEET} would start producing bookings with no
 * join link the day its owner switched to Outlook. A {@code DEFAULT} event type just follows.
 *
 * <p>The <b>writer</b> is whoever receives the calendar event and therefore mints the link:
 * {@code booking.getHostId()}, which is the owner for 1:1/group/collective and the <em>assigned
 * member</em> for round-robin. So a round-robin booking resolves the assigned member's own default,
 * onto their own calendar — no kind-specific branching anywhere.
 */
@Component
public class EventConferencingResolver {
    private static final Logger log = LoggerFactory.getLogger(EventConferencingResolver.class);

    private final UserRepository userRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final NativeConferencingCapabilityService capabilityService;

    public EventConferencingResolver(UserRepository userRepository,
                                     CalendarConnectionRepository connectionRepository,
                                     NativeConferencingCapabilityService capabilityService) {
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.capabilityService = capabilityService;
    }

    /**
     * Resolve the provider that will actually be used for this booking's meeting link.
     *
     * @param writerUserId whoever receives the calendar event — {@code booking.getHostId()}
     */
    public ConferencingProviderType resolve(UUID writerUserId, EventType eventType) {
        return resolve(writerUserId, eventType == null ? null : eventType.getConferencingProvider());
    }

    public ConferencingProviderType resolve(UUID writerUserId, ConferencingProviderType stored) {
        if (stored == null) {
            return ConferencingProviderType.NONE;
        }
        if (!stored.isPointer()) {
            return stored;
        }
        return defaultFor(writerUserId);
    }

    /**
     * The user's global default meeting link, re-derived against their <em>current</em> write-back
     * calendar rather than trusted blindly.
     *
     * <p>The settings page refuses to leave these two inconsistent, so the guard below should never
     * fire. If it does, preserve the configured provider: the booking capability guard can then
     * reject confirmation explicitly, and the outbox can defer any notification that depends on a
     * provider-created link. Silently degrading to {@code NONE} would confirm and email a meeting
     * with no way to join it.
     */
    public ConferencingProviderType defaultFor(UUID userId) {
        ConferencingProviderType preference = userRepository.findById(userId)
                .map(User::getDefaultConferencingProvider)
                .orElse(ConferencingProviderType.NONE);

        if (preference == null || preference == ConferencingProviderType.NONE) {
            return ConferencingProviderType.NONE;
        }
        if (!preference.requiresCalendarProvider()) {
            return preference;   // Zoom: no calendar provider needed, always serviceable.
        }

        CalendarConnection writeback = connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)
                .orElse(null);
        if (writeback == null || !capabilityService.canServe(writeback, preference)) {
            log.warn("conferencing_default_unserviceable userId={} default={} writebackProvider={} "
                            + "-- preserving configured provider for explicit capability rejection",
                    userId, preference,
                    writeback == null ? "none" : writeback.getProvider());
        }
        return preference;
    }

}
