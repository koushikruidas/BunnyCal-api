package io.bunnycal.admin.users.dto;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One connected calendar account, with the sub-calendars it exposes.
 *
 * <p>Deliberately omits every credential field on the entity — refresh-token ciphertext,
 * sync cursors, webhook channel ids. Admins need to know whether a connection is healthy,
 * never what it is authenticated with.
 *
 * <p>{@code checksAvailability} on each calendar is the field that explains missing
 * booking slots: a calendar with it set contributes busy time and can suppress slots,
 * and a user's busy set spans every connection they hold, not just the obvious one.
 */
public record AdminUserCalendarConnectionDto(
        UUID id,
        CalendarProviderType provider,
        String accountEmail,
        CalendarConnectionStatus status,
        Instant lastSyncedAt,
        String lastErrorCode,
        Instant lastErrorAt,
        int failureCount,
        long syncedEventCount,
        Instant createdAt,
        List<Calendar> calendars) {

    /** A single sub-calendar within the connected account. */
    public record Calendar(
            String externalCalendarId,
            String name,
            boolean primary,
            boolean selected,
            boolean checksAvailability,
            boolean canWrite,
            boolean hidden,
            Instant lastSyncedAt) {

        static Calendar from(CalendarConnectionCalendar c) {
            return new Calendar(
                    c.getExternalCalendarId(),
                    c.getName(),
                    c.isPrimary(),
                    c.isSelected(),
                    c.isChecksAvailability(),
                    c.isCanWrite(),
                    c.isHidden(),
                    c.getLastSyncedAt());
        }
    }

    public static AdminUserCalendarConnectionDto from(
            CalendarConnection conn,
            List<CalendarConnectionCalendar> calendars,
            long syncedEventCount) {
        return new AdminUserCalendarConnectionDto(
                conn.getId(),
                conn.getProvider(),
                conn.getAccountEmail(),
                conn.getStatus(),
                conn.getLastSyncedAt(),
                conn.getLastErrorCode(),
                conn.getLastErrorAt(),
                conn.getFailureCount(),
                syncedEventCount,
                conn.getCreatedAt(),
                calendars.stream().map(Calendar::from).toList());
    }
}
