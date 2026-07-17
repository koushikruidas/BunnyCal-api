package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarRole;
import org.junit.jupiter.api.Test;

class AvailabilityCalendarPolicyTest {
    private final AvailabilityCalendarPolicy policy = new AvailabilityCalendarPolicy();

    @Test
    void versionOneContract_includesOnlyVisibleReadableEnabledPrimaryCalendars() {
        CalendarConnection connection = connected();
        CalendarConnectionCalendar calendar = primary();
        assertThat(policy.evaluate(connection, calendar))
                .isEqualTo(new AvailabilityCalendarPolicy.Decision(
                        true, AvailabilityCalendarPolicy.Reason.ENABLED));

        calendar.setChecksAvailability(false);
        assertThat(policy.evaluate(connection, calendar).reason())
                .isEqualTo(AvailabilityCalendarPolicy.Reason.CHECKS_AVAILABILITY_FALSE);

        calendar = primary();
        calendar.setCanRead(false);
        assertThat(policy.evaluate(connection, calendar).reason())
                .isEqualTo(AvailabilityCalendarPolicy.Reason.NOT_READABLE);

        calendar = primary();
        calendar.setHidden(true);
        assertThat(policy.evaluate(connection, calendar).reason())
                .isEqualTo(AvailabilityCalendarPolicy.Reason.HIDDEN);

        calendar = primary();
        calendar.setCalendarRole(CalendarRole.OTHER);
        assertThat(policy.evaluate(connection, calendar).reason())
                .isEqualTo(AvailabilityCalendarPolicy.Reason.NOT_PRIMARY);
    }

    @Test
    void versionOneContract_excludesDisconnectedConnections() {
        CalendarConnection connection = connected();
        connection.setStatus(CalendarConnectionStatus.REVOKED);

        assertThat(policy.evaluate(connection, primary()).reason())
                .isEqualTo(AvailabilityCalendarPolicy.Reason.CONNECTION_DISCONNECTED);
    }

    private static CalendarConnection connected() {
        CalendarConnection connection = new CalendarConnection();
        connection.setStatus(CalendarConnectionStatus.ACTIVE);
        return connection;
    }

    private static CalendarConnectionCalendar primary() {
        CalendarConnectionCalendar calendar = new CalendarConnectionCalendar();
        calendar.setCalendarRole(CalendarRole.PRIMARY);
        calendar.setCanRead(true);
        calendar.setHidden(false);
        calendar.setChecksAvailability(true);
        return calendar;
    }
}
