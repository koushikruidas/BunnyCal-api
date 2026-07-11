package io.bunnycal.calendar.service;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.logging.OpsLoggers;
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
    private final CalendarOAuthService googleOAuthService;
    private final MicrosoftCalendarOAuthService microsoftOAuthService;
    private final SlotCacheVersionService slotCacheVersionService;

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
     * Re-points round-robin write-back at a different connected account. Clearing the old flag
     * before setting the new one keeps the partial unique index satisfied throughout — it forbids
     * two TRUE rows for a user, not zero.
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
        OpsLoggers.HOST.info("calendar_default_writeback_changed hostId={} connectionId={} provider={}",
                userId, connectionId, connection.getProvider());
    }

    /**
     * After the write-back target is disconnected, hand the flag to the user's oldest remaining
     * live connection so round-robin keeps working without them having to notice.
     */
    private void promoteReplacementWriteback(UUID userId, UUID disconnectedConnectionId) {
        connectionRepository.clearDefaultWritebackForUser(userId);
        List<CalendarConnection> remaining = connectionRepository
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, CalendarConnectionStatus.ACTIVE);
        remaining.stream()
                .filter(c -> !c.getId().equals(disconnectedConnectionId))
                .findFirst()
                .ifPresent(replacement -> {
                    replacement.setDefaultWriteback(true);
                    connectionRepository.save(replacement);
                    OpsLoggers.HOST.info(
                            "calendar_default_writeback_promoted hostId={} connectionId={} reason=previous_disconnected",
                            userId, replacement.getId());
                });
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
