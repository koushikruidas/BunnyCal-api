package io.bunnycal.calendar.service;

import java.util.UUID;

/**
 * The calendar connection row could not be read in this transaction.
 *
 * <p>Distinct from a sync fault on purpose. Sign-in creates the connection and runs its initial
 * full sync inside a single transaction ({@code CalendarOAuthService.connectAuthorizedUser}), so
 * for the few seconds before that commits the row is invisible to every other transaction — while
 * already being SYNCING, which the scheduler's due-query selects. A sweep landing in that window
 * therefore picks up a connection it cannot then read.
 *
 * <p>That is a "not yet", not a "broken". Treating it as a sync failure marked freshly-created
 * connections FAILED, which stopped onboarding from setting the conferencing default and left new
 * hosts being told to reconnect a calendar that was fine — and that recovered on its own one sweep
 * later, by which time onboarding had already given up.
 *
 * <p>Callers should skip the connection and let the next sweep pick it up.
 */
public class CalendarConnectionNotVisibleException extends RuntimeException {

    private final UUID connectionId;

    public CalendarConnectionNotVisibleException(UUID connectionId) {
        super("Calendar connection " + connectionId + " is not visible in this transaction yet");
        this.connectionId = connectionId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }
}
