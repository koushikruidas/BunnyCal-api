package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarRole;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Version 1 of the single business rule that decides whether a connected calendar contributes
 * free/busy time to availability.
 *
 * <p>Contract invariants:
 * <ul>
 *   <li>deterministic and side-effect free;</li>
 *   <li>the same connection/calendar state always produces the same decision;</li>
 *   <li>slot listing, confirmation, the Availability UI, round-robin, and collective scheduling
 *       must route through the canonical busy-time service backed by this policy;</li>
 *   <li>sync may compose this decision with an independent writeback-sync decision, but writeback
 *       selection must never make a calendar contribute to availability.</li>
 * </ul>
 *
 * <p>This is deliberately one concrete policy, not a provider strategy. Google and Microsoft
 * calendars obey the same availability rule.
 */
@Component
public final class AvailabilityCalendarPolicy {
    public static final int CONTRACT_VERSION = 1;

    public Decision evaluate(CalendarConnection connection, CalendarConnectionCalendar calendar) {
        if (connection == null) {
            return Decision.exclude(Reason.CONNECTION_MISSING);
        }
        if (connection.getStatus() == CalendarConnectionStatus.REVOKED
                || connection.getStatus() == CalendarConnectionStatus.DISCONNECTED) {
            return Decision.exclude(Reason.CONNECTION_DISCONNECTED);
        }
        if (calendar == null) {
            return Decision.exclude(Reason.CALENDAR_MISSING);
        }
        if (!Objects.equals(connection.getId(), calendar.getConnectionId())) {
            return Decision.exclude(Reason.CONNECTION_MISMATCH);
        }
        if (calendar.getCalendarRole() != CalendarRole.PRIMARY) {
            return Decision.exclude(Reason.NOT_PRIMARY);
        }
        if (calendar.isHidden()) {
            return Decision.exclude(Reason.HIDDEN);
        }
        if (!calendar.isCanRead()) {
            return Decision.exclude(Reason.NOT_READABLE);
        }
        if (!calendar.isChecksAvailability()) {
            return Decision.exclude(Reason.CHECKS_AVAILABILITY_FALSE);
        }
        return Decision.include();
    }

    public boolean contributesToAvailability(CalendarConnection connection,
                                             CalendarConnectionCalendar calendar) {
        return evaluate(connection, calendar).included();
    }

    public enum Reason {
        ENABLED,
        CONNECTION_MISSING,
        CONNECTION_DISCONNECTED,
        CONNECTION_MISMATCH,
        CALENDAR_MISSING,
        NOT_PRIMARY,
        HIDDEN,
        NOT_READABLE,
        CHECKS_AVAILABILITY_FALSE
    }

    public record Decision(boolean included, Reason reason) {
        private static Decision include() {
            return new Decision(true, Reason.ENABLED);
        }

        private static Decision exclude(Reason reason) {
            return new Decision(false, reason);
        }
    }
}
