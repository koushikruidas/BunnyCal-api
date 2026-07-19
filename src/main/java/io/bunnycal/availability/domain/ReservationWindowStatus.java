package io.bunnycal.availability.domain;

/**
 * Lifecycle of a {@link GroupEventReservationWindow}.
 *
 * <p>Windows are retired rather than deleted so that sessions they generated keep
 * resolvable lineage. Every slot-generation read path filters to {@link #ACTIVE}.
 */
public enum ReservationWindowStatus {

    /** Live: generates slots and blocks the host's other event types. */
    ACTIVE,

    /**
     * Historical: no longer generates slots, retained so pinned sessions can still
     * resolve the rule that created them.
     *
     * <p>Deliberately not overloaded for temporary pauses. A rule the host wants
     * paused but kept (teacher on leave) should get a distinct {@code DISABLED}
     * state rather than being retired, since retirement is meant to be permanent.
     */
    RETIRED
}
