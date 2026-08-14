package io.bunnycal.calendar.provider;

import java.util.UUID;

/**
 * @param targetCalendarId the provider calendar holding the event, or null to use the connection's
 *        default. Required for Microsoft: Graph addresses events under a concrete calendar id and
 *        has no literal "primary" alias, so omitting it produced a 400 that the reconciler read as
 *        a permanent observation failure and recorded as PROVIDER_STATE_ORPHANED — on bookings
 *        whose event was in fact written and syncing. Google does accept "primary", which is how
 *        the constant came to be shared by both.
 */
public record DeleteEventRequest(UUID connectionId,
                                 String externalEventId,
                                 String targetCalendarId) {

    /** Providers whose default calendar alias is addressable, i.e. Google. */
    public DeleteEventRequest(UUID connectionId, String externalEventId) {
        this(connectionId, externalEventId, null);
    }
}
