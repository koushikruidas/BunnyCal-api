package com.daedalussystems.easySchedule.booking.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BookingStateTransitionsTest {

    @Test
    void pendingAllowedTransitions() {
        Set<BookingState> allowed = BookingStateTransitions.allowedFrom(BookingState.PENDING);
        assertEquals(
                Set.of(
                        BookingState.CONFIRMED,
                        BookingState.CANCELLED,
                        BookingState.EXPIRED,
                        BookingState.REJECTED),
                allowed);
    }

    @Test
    void confirmedAllowedTransitions() {
        Set<BookingState> allowed = BookingStateTransitions.allowedFrom(BookingState.CONFIRMED);
        assertEquals(Set.of(BookingState.CANCELLED, BookingState.COMPLETED), allowed);
    }

    @Test
    void everyAllowedTransitionReturnsTrue() {
        for (BookingState from : BookingState.values()) {
            for (BookingState to : BookingStateTransitions.allowedFrom(from)) {
                assertTrue(
                        BookingStateTransitions.isAllowed(from, to),
                        "Expected " + from + " -> " + to + " to be allowed");
            }
        }
    }

    @Test
    void pendingToCompletedIsForbidden() {
        assertFalse(BookingStateTransitions.isAllowed(BookingState.PENDING, BookingState.COMPLETED));
        CustomException ex = assertThrows(
                CustomException.class,
                () -> BookingStateTransitions.requireAllowed(BookingState.PENDING, BookingState.COMPLETED));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("PENDING -> COMPLETED"));
    }

    /** Per review: explicit assertion that a confirmed booking cannot expire. */
    @Test
    void confirmedToExpiredIsForbidden() {
        assertFalse(BookingStateTransitions.isAllowed(BookingState.CONFIRMED, BookingState.EXPIRED));
        CustomException ex = assertThrows(
                CustomException.class,
                () -> BookingStateTransitions.requireAllowed(BookingState.CONFIRMED, BookingState.EXPIRED));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("CONFIRMED -> EXPIRED"));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (BookingState s : BookingState.values()) {
            if (s.isTerminal()) {
                assertTrue(
                        BookingStateTransitions.allowedFrom(s).isEmpty(),
                        "Terminal state " + s + " must have no outgoing transitions");
            }
        }
    }

    @Test
    void terminalStateOutgoingTransitionAlwaysThrows() {
        for (BookingState terminal : BookingState.values()) {
            if (!terminal.isTerminal()) continue;
            for (BookingState target : BookingState.values()) {
                if (target == terminal) continue; // self-transition is a no-op
                assertFalse(
                        BookingStateTransitions.isAllowed(terminal, target),
                        terminal + " -> " + target + " must be forbidden (Invariant #5)");
            }
        }
    }

    @Test
    void terminalConsistentWithEmptyOutSet() {
        for (BookingState s : BookingState.values()) {
            assertEquals(
                    s.isTerminal(),
                    BookingStateTransitions.allowedFrom(s).isEmpty(),
                    "isTerminal() and allowedFrom().isEmpty() must agree for " + s);
        }
    }

    @Test
    void selfTransitionIsNoOpForEveryState() {
        for (BookingState s : BookingState.values()) {
            assertTrue(
                    BookingStateTransitions.isAllowed(s, s),
                    "Self-transition " + s + " -> " + s + " must be allowed (Invariant #6)");
            assertDoesNotThrow(
                    () -> BookingStateTransitions.requireAllowed(s, s),
                    "Self-transition " + s + " -> " + s + " must not throw (Invariant #6)");
            assertDoesNotThrow(
                    () -> BookingStateTransitions.requireAllowed(s, s, "retry"),
                    "Self-transition with reason must not throw");
        }
    }

    @Test
    void requireAllowedIncludesReasonInErrorMessage() {
        CustomException ex = assertThrows(
                CustomException.class,
                () -> BookingStateTransitions.requireAllowed(
                        BookingState.EXPIRED, BookingState.CONFIRMED, "retry-from-worker"));
        assertTrue(ex.getMessage().contains("EXPIRED -> CONFIRMED"));
        assertTrue(ex.getMessage().contains("retry-from-worker"));
    }

    @Test
    void requireAllowedOmitsReasonWhenNull() {
        CustomException ex = assertThrows(
                CustomException.class,
                () -> BookingStateTransitions.requireAllowed(
                        BookingState.EXPIRED, BookingState.CONFIRMED, null));
        assertFalse(ex.getMessage().contains("reason:"));
    }

    @Test
    void nullArgumentsThrowNpeWithUsefulMessage() {
        NullPointerException fromNpe = assertThrows(
                NullPointerException.class,
                () -> BookingStateTransitions.requireAllowed(null, BookingState.CONFIRMED));
        assertNotNull(fromNpe.getMessage());
        assertTrue(fromNpe.getMessage().contains("from"));

        NullPointerException toNpe = assertThrows(
                NullPointerException.class,
                () -> BookingStateTransitions.requireAllowed(BookingState.PENDING, null));
        assertNotNull(toNpe.getMessage());
        assertTrue(toNpe.getMessage().contains("to"));

        assertThrows(
                NullPointerException.class,
                () -> BookingStateTransitions.isAllowed(null, BookingState.CONFIRMED));
        assertThrows(
                NullPointerException.class,
                () -> BookingStateTransitions.allowedFrom(null));
    }

    @Test
    void allowedFromReturnsUnmodifiableSet() {
        Set<BookingState> allowed = BookingStateTransitions.allowedFrom(BookingState.PENDING);
        assertThrows(
                UnsupportedOperationException.class,
                () -> allowed.add(BookingState.COMPLETED));
    }

    /**
     * Implicit class-load completeness check — touching the class triggers
     * the static initializer; this test fails (with IllegalStateException)
     * if a future enum constant is added without a transitions entry.
     */
    @Test
    void classLoadsCleanlyWithCurrentStateSet() {
        assertEquals(BookingState.values().length, 6);
        for (BookingState s : BookingState.values()) {
            assertNotNull(BookingStateTransitions.allowedFrom(s),
                    "Missing transitions entry for " + s.name());
        }
    }
}
