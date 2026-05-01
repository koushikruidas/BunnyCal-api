package com.daedalussystems.easySchedule.booking.contract;

import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Single source of truth for allowed booking state transitions.
 *
 * <p>Any code that mutates a booking's state MUST funnel through
 * {@link #requireAllowed(BookingState, BookingState, String)}. There are no
 * other sanctioned ways to change state.
 *
 * <p>Self-transitions ({@code from == to}) are no-ops, not errors. This
 * supports at-least-once async processing (Invariant&nbsp;#6): a duplicate
 * worker invocation that re-applies the same target state must not crash.
 *
 * <p>See {@code booking/system_contracts.md} for the authoritative spec.
 */
public final class BookingStateTransitions {

    private static final Map<BookingState, Set<BookingState>> TRANSITIONS;

    static {
        EnumMap<BookingState, Set<BookingState>> m = new EnumMap<>(BookingState.class);
        m.put(BookingState.PENDING,   Set.of(BookingState.CONFIRMED, BookingState.CANCELLED, BookingState.EXPIRED, BookingState.REJECTED));
        m.put(BookingState.CONFIRMED, Set.of(BookingState.CANCELLED, BookingState.COMPLETED));
        m.put(BookingState.CANCELLED, Set.of());
        m.put(BookingState.EXPIRED,   Set.of());
        m.put(BookingState.COMPLETED, Set.of());
        m.put(BookingState.REJECTED,  Set.of());

        for (BookingState s : BookingState.values()) {
            if (!m.containsKey(s)) {
                throw new IllegalStateException("Missing transition entry for " + s.name());
            }
        }
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private BookingStateTransitions() {}

    /**
     * Returns true if transitioning from {@code from} to {@code to} is allowed.
     * Self-transitions ({@code from == to}) always return true (Invariant&nbsp;#6).
     */
    public static boolean isAllowed(BookingState from, BookingState to) {
        Objects.requireNonNull(from, "from state must not be null");
        Objects.requireNonNull(to,   "to state must not be null");
        if (from == to) {
            return true;
        }
        return TRANSITIONS.get(from).contains(to);
    }

    /**
     * Returns the unmodifiable set of states reachable in one transition from
     * {@code from}. Terminal states return an empty set.
     */
    public static Set<BookingState> allowedFrom(BookingState from) {
        Objects.requireNonNull(from, "from state must not be null");
        return TRANSITIONS.get(from);
    }

    /**
     * Convenience overload &mdash; equivalent to
     * {@link #requireAllowed(BookingState, BookingState, String)} with
     * {@code reason = null}.
     */
    public static void requireAllowed(BookingState from, BookingState to) {
        requireAllowed(from, to, null);
    }

    /**
     * Canonical enforcement entry point. Throws if the transition is illegal.
     *
     * <p>The {@code reason} parameter is currently included only in the error
     * message, but exists in the signature from day one so future audit /
     * event-emission can be added without a breaking change.
     */
    public static void requireAllowed(BookingState from, BookingState to, String reason) {
        Objects.requireNonNull(from, "from state must not be null");
        Objects.requireNonNull(to,   "to state must not be null");
        if (from == to) {
            return;
        }
        Set<BookingState> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new CustomException(
                    ErrorCode.VALIDATION_ERROR,
                    "Illegal booking state transition: " + from.name() + " -> " + to.name()
                            + (reason != null ? " (reason: " + reason + ")" : ""));
        }
    }
}
