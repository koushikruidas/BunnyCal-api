package io.bunnycal.calendar.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarRole;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.conferencing.service.NativeConferencingCapabilityService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operations that address one specific connected account rather than "the user's connection for
 * provider X". Since V118_0 a user may hold several accounts per provider, so the provider-scoped
 * routes are no longer sufficient — removing "your Google calendar" is ambiguous once there are two.
 */
@Service
@RequiredArgsConstructor
public class CalendarConnectionManagementService {

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository inventoryRepository;
    private final UserRepository userRepository;
    private final CalendarOAuthService googleOAuthService;
    private final MicrosoftCalendarOAuthService microsoftOAuthService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final NativeConferencingCapabilityService conferencingCapabilityService;

    /**
     * Disconnects one account. Revoking upstream, marking the row REVOKED and clearing its token
     * is delegated to the provider service; what this adds is the ownership check, promotion of a
     * replacement write-back target, and the slot-cache invalidation (the removed account's busy
     * times must stop blocking slots immediately).
     */
    @Transactional
    public void disconnect(UUID userId, UUID connectionId) {
        CalendarConnection connection = requireOwnedConnection(userId, connectionId);

        switch (connection.getProvider()) {
            case GOOGLE -> googleOAuthService.disconnectConnection(connection);
            case MICROSOFT -> microsoftOAuthService.disconnectConnection(connection);
        }

        if (connection.isDefaultWriteback()) {
            promoteReplacementWriteback(userId, connectionId);
        }
        slotCacheVersionService.bumpVersionAfterCommit(userId);
    }

    /**
     * Points the user's bookings at a different connected account.
     *
     * <p>If the target cannot mint the current native meeting link — moving to Outlook while the
     * default is Google Meet, for example — the write-back move still succeeds and the incompatible
     * link stands down to {@code NONE}. Refusing both changes creates a settings deadlock: Teams
     * cannot be selected until Microsoft is write-back, while Microsoft cannot become write-back
     * until Meet is changed. Standing down is safe, visible, and lets the user choose the new native
     * provider immediately afterward.
     *
     * <p>Clearing the old flag before setting the new one keeps the partial unique index satisfied
     * throughout — it forbids two TRUE rows for a user, not zero.
     */
    @Transactional
    public void setDefaultWriteback(UUID userId, UUID connectionId) {
        CalendarConnection connection = requireOwnedConnection(userId, connectionId);
        if (connection.getStatus() != CalendarConnectionStatus.ACTIVE
                && connection.getStatus() != CalendarConnectionStatus.SYNCING) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Bookings can only be added to a connected calendar.");
        }
        if (connection.isDefaultWriteback()) {
            return;
        }

        connectionRepository.clearDefaultWritebackForUser(userId);
        connection.setDefaultWriteback(true);
        connectionRepository.save(connection);
        standDownDefaultLinkIfUnservable(userId, connection);
        slotCacheVersionService.bumpVersionAfterCommit(userId);
        OpsLoggers.HOST.info("calendar_default_writeback_changed hostId={} connectionId={} provider={}",
                userId, connectionId, connection.getProvider());
    }

    /**
     * Chooses which calendar <em>within</em> the write-back connection receives bookings. At most one
     * per connection, enforced by a partial unique index.
     */
    @Transactional
    public void setWritebackCalendar(UUID userId, UUID connectionId, String externalCalendarId) {
        requireOwnedConnection(userId, connectionId);
        CalendarConnectionCalendar calendar = inventoryRepository
                .findByConnectionIdAndExternalCalendarId(connectionId, externalCalendarId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Calendar not found."));
        if (!calendar.isCanWrite()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "You have read-only access to this calendar, so bookings cannot be added to it.");
        }
        if (calendar.isSelected()) {
            return;
        }
        inventoryRepository.findByConnectionIdAndSelectedTrue(connectionId).ifPresent(previous -> {
            previous.setSelected(false);
            inventoryRepository.save(previous);
        });
        calendar.setSelected(true);
        inventoryRepository.save(calendar);
        CalendarConnection connection = requireOwnedConnection(userId, connectionId);
        if (connection.isDefaultWriteback()) {
            standDownDefaultLinkIfUnservable(userId, connection);
        }
        OpsLoggers.HOST.info("calendar_writeback_calendar_changed hostId={} connectionId={} calendarId={}",
                userId, connectionId, externalCalendarId);
    }

    /**
     * Turns a calendar's free/busy contribution on or off.
     *
     * <p>This holds everywhere the user is booked — their own event types and any team event they
     * are a member of. That uniformity is the point: under the old per-event-type model a user could
     * exclude a noisy calendar from their own events and then have it silently start blocking them
     * again when a colleague added them to a round-robin.
     */
    @Transactional
    public void setChecksAvailability(UUID userId, UUID connectionId, String externalCalendarId, boolean checks) {
        requireOwnedConnection(userId, connectionId);
        CalendarConnectionCalendar calendar = inventoryRepository
                .findByConnectionIdAndExternalCalendarId(connectionId, externalCalendarId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Calendar not found."));
        if (calendar.getCalendarRole() != CalendarRole.PRIMARY) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Only the primary calendar can be checked for booking conflicts.");
        }
        if (calendar.isChecksAvailability() == checks) {
            return;
        }
        calendar.setChecksAvailability(checks);
        inventoryRepository.save(calendar);
        // Slots already computed were generated against the old answer.
        slotCacheVersionService.bumpVersionAfterCommit(userId);
        OpsLoggers.HOST.info("calendar_availability_changed hostId={} connectionId={} calendarId={} checksAvailability={}",
                userId, connectionId, externalCalendarId, checks);
    }

    /**
     * Sets the user's default meeting link — the one every event type that has not been given an
     * explicit override will follow, now and after any later change.
     *
     * <p>Constrained by the write-back calendar, for the same reason as {@link #setDefaultWriteback}:
     * only a Google calendar can mint a Meet link, and only a Microsoft work/school account can mint
     * a Teams one. Zoom and "no link" are always available.
     */
    @Transactional
    public void setDefaultConferencing(UUID userId, ConferencingProviderType provider) {
        if (provider == null || provider == ConferencingProviderType.DEFAULT) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Choose a meeting link: Google Meet, Microsoft Teams, Zoom, or none.");
        }
        if (provider == ConferencingProviderType.CUSTOM_URL) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "A custom link is set per event, not as your default.");
        }
        User user = requireUser(userId);

        if (provider.requiresCalendarProvider()) {
            CalendarConnection writeback = connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)
                    .orElse(null);
            if (writeback == null) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        describe(provider) + " needs " + requiredCalendarFor(provider)
                                + " to create the meeting on. Connect one first.");
            }
            if (!conferencingCapabilityService.canServe(writeback, provider)) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        describe(provider) + " can only be created on " + requiredCalendarFor(provider)
                                + ", but your bookings go to your " + writeback.getProvider()
                                + " calendar. Move your bookings there first, or choose Zoom.");
            }
        }

        user.setDefaultConferencingProvider(provider);
        userRepository.save(user);
        OpsLoggers.HOST.info("default_conferencing_changed hostId={} provider={}", userId, provider);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private static String describe(ConferencingProviderType provider) {
        return switch (provider) {
            case GOOGLE_MEET -> "Google Meet";
            case MICROSOFT_TEAMS -> "Microsoft Teams";
            case ZOOM -> "Zoom";
            case CUSTOM_URL -> "a custom link";
            case NONE, DEFAULT -> "no meeting link";
        };
    }

    private static String requiredCalendarFor(ConferencingProviderType provider) {
        return provider == ConferencingProviderType.MICROSOFT_TEAMS
                ? "a Microsoft work or school calendar"
                : "a Google calendar";
    }

    /**
     * After the write-back target is disconnected, hand the flag to the user's oldest remaining live
     * connection so their bookings keep landing somewhere without them having to notice.
     *
     * <p>If the replacement cannot mint their default meeting link — they disconnected Google while
     * their default was Meet — the default is stood down to {@code NONE} rather than left pointing at
     * something unbuildable. Bookings then carry no join link, which is visible and fixable, instead
     * of failing at confirmation time for a guest.
     */
    private void promoteReplacementWriteback(UUID userId, UUID disconnectedConnectionId) {
        connectionRepository.clearDefaultWritebackForUser(userId);
        List<CalendarConnection> remaining = connectionRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE);
        remaining.stream()
                .filter(c -> !c.getId().equals(disconnectedConnectionId))
                .findFirst()
                .ifPresentOrElse(replacement -> {
                    replacement.setDefaultWriteback(true);
                    connectionRepository.save(replacement);
                    OpsLoggers.HOST.info(
                            "calendar_default_writeback_promoted hostId={} connectionId={} reason=previous_disconnected",
                            userId, replacement.getId());
                    standDownDefaultLinkIfUnservable(userId, replacement);
                }, () -> standDownDefaultLinkIfUnservable(userId, null));
    }

    private void standDownDefaultLinkIfUnservable(UUID userId, CalendarConnection writeback) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        ConferencingProviderType current = user.getDefaultConferencingProvider();
        if (current == null || !current.requiresCalendarProvider()) {
            return;
        }
        if (conferencingCapabilityService.canServe(writeback, current)) {
            return;
        }
        user.setDefaultConferencingProvider(ConferencingProviderType.NONE);
        userRepository.save(user);
        OpsLoggers.HOST.warn(
                "default_conferencing_stood_down hostId={} previous={} reason=writeback_cannot_serve_it",
                userId, current);
    }

    /**
     * An id-addressed route gets no ownership guarantee from the query the way the old
     * user-scoped provider routes did, so it has to be checked explicitly. 404 rather than 403 —
     * another user's connection should not be distinguishable from one that does not exist.
     */
    private CalendarConnection requireOwnedConnection(UUID userId, UUID connectionId) {
        return connectionRepository.findById(connectionId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Calendar connection not found."));
    }
}
