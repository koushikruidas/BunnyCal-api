package com.daedalussystems.easySchedule.booking.contract;

import lombok.AllArgsConstructor;

/**
 * Lifecycle states a booking can be in.
 *
 * <p>Each state carries two pieces of metadata:
 * <ul>
 *   <li>{@link #isTerminal()}      &mdash; whether the state is final (no
 *       outgoing transitions). Encodes Invariant&nbsp;#2 and Invariant&nbsp;#5.</li>
 *   <li>{@link #blocksTimeSlot()}  &mdash; whether a booking in this state
 *       reserves capacity (a time slot) for its host. Encodes Invariant&nbsp;#1.</li>
 * </ul>
 *
 * <p>See {@code booking/system_contracts.md} for the authoritative spec.
 */
@AllArgsConstructor
public enum BookingState {

    PENDING(  /* terminal */ false, /* blocksTimeSlot */ true),
    CONFIRMED(/* terminal */ false, /* blocksTimeSlot */ true),
    CANCELLED(/* terminal */ true,  /* blocksTimeSlot */ false),
    EXPIRED(  /* terminal */ true,  /* blocksTimeSlot */ false),
    COMPLETED(/* terminal */ true,  /* blocksTimeSlot */ false),
    REJECTED( /* terminal */ true,  /* blocksTimeSlot */ false);

    private final boolean terminal;
    private final boolean blocksTimeSlot;

    /**
     * Returns true if this state is terminal &mdash; a booking that reaches
     * this state cannot transition to any other state (Invariant&nbsp;#5).
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Returns true if a booking in this state reserves capacity (a time slot)
     * for its host. Overlap-prevention queries MUST filter on this predicate
     * rather than hard-coding the {PENDING, CONFIRMED} set.
     *
     * <p><b>IMPORTANT:</b> any new state that occupies a time slot MUST return
     * true here. Forgetting to set this on a new state silently breaks
     * Invariant&nbsp;#1 (no two bookings overlap for the same host).
     */
    public boolean blocksTimeSlot() {
        return blocksTimeSlot;
    }

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    public static BookingState fromStatus(String status) {
        return BookingState.valueOf(status);
    }
}
