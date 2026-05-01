package com.daedalussystems.easySchedule.booking.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BookingStateTest {

    @Test
    void pendingAndConfirmedAreNonTerminalAndBlockTimeSlot() {
        assertFalse(BookingState.PENDING.isTerminal());
        assertTrue(BookingState.PENDING.blocksTimeSlot());

        assertFalse(BookingState.CONFIRMED.isTerminal());
        assertTrue(BookingState.CONFIRMED.blocksTimeSlot());
    }

    @Test
    void terminalStatesDoNotBlockTimeSlot() {
        assertTrue(BookingState.CANCELLED.isTerminal());
        assertTrue(BookingState.EXPIRED.isTerminal());
        assertTrue(BookingState.COMPLETED.isTerminal());
        assertTrue(BookingState.REJECTED.isTerminal());

        assertFalse(BookingState.CANCELLED.blocksTimeSlot());
        assertFalse(BookingState.EXPIRED.blocksTimeSlot());
        assertFalse(BookingState.COMPLETED.blocksTimeSlot());
        assertFalse(BookingState.REJECTED.blocksTimeSlot());
    }

    /**
     * Cross-check invariant: no state may be simultaneously terminal AND
     * slot-blocking. A future state declared inconsistently (e.g. EXPIRED
     * with blocksTimeSlot=true) would silently break Invariant #1; this
     * test fails the build before that can happen.
     */
    @Test
    void noStateIsBothTerminalAndSlotBlocking() {
        for (BookingState s : BookingState.values()) {
            assertFalse(
                    s.isTerminal() && s.blocksTimeSlot(),
                    "State " + s.name() + " is both terminal and slot-blocking — Invariant #1 risk");
        }
    }
}
