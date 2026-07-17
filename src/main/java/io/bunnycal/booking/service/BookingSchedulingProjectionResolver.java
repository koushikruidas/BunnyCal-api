package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.AvailabilityCalendarPolicy;
import io.bunnycal.common.logging.OpsLoggers;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the calendar a booking is written to.
 *
 * <p>Write-back is a property of the <b>user</b>, not of the event type: one connection carries
 * {@code is_default_writeback}, and one calendar within it carries {@code is_selected}. There is no
 * per-event-type destination to consult, so every event kind resolves the same way — against
 * whoever actually receives the calendar event.
 *
 * <p>That writer is {@code booking.getHostId()}: the owner for 1:1/group/collective, and the
 * <em>assigned member</em> for round-robin. A round-robin booking therefore lands on the assigned
 * member's own calendar, chosen by them, which is the only calendar the owner could never have
 * picked for them anyway.
 */
@Service
public class BookingSchedulingProjectionResolver {

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository inventoryRepository;
    private final AvailabilityCalendarPolicy availabilityPolicy;

    public BookingSchedulingProjectionResolver(CalendarConnectionRepository connectionRepository,
                                                CalendarConnectionCalendarRepository inventoryRepository,
                                                AvailabilityCalendarPolicy availabilityPolicy) {
        this.connectionRepository = connectionRepository;
        this.inventoryRepository = inventoryRepository;
        this.availabilityPolicy = availabilityPolicy;
    }

    @Nullable
    public SchedulingProjection resolve(Booking booking, EventType eventType) {
        return resolveForUser(booking.getHostId());
    }

    /**
     * The user's global write-back destination, or null if they have none — no active connection, or
     * a connection whose inventory has not been hydrated.
     *
     * <p>Null means "do not write to a calendar", which is a legitimate state (the guest still gets
     * an ICS invitation). It is <b>not</b> a licence to guess: picking some other connection the user
     * did not choose is how bookings end up in the wrong account.
     */
    @Nullable
    public SchedulingProjection resolveForUser(UUID userId) {
        CalendarConnection connection = writebackConnection(userId).orElse(null);
        if (connection == null || connection.getProvider() == null) {
            return null;
        }
        String calendarId = writebackCalendarId(connection);
        if (calendarId == null || calendarId.isBlank()) {
            return null;
        }
        return new SchedulingProjection(connection.getProvider(), connection.getId(), calendarId);
    }

    /**
     * The connection the user nominated to receive their bookings.
     *
     * <p>There is deliberately <b>no</b> fall-back to "some other connection" when the nominated one
     * is missing. The previous implementation quietly picked the oldest connection of <em>any</em>
     * provider, which meant a booking could land in a Google account when the user had chosen
     * Outlook. Under a global model the user has told us where their bookings go; if that is
     * unavailable, the honest answer is nowhere.
     */
    public Optional<CalendarConnection> writebackConnection(UUID userId) {
        List<CalendarConnection> active = connectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(
                userId, CalendarConnectionStatus.ACTIVE);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Optional<CalendarConnection> designated = active.stream()
                .filter(CalendarConnection::isDefaultWriteback)
                .findFirst();
        if (designated.isEmpty()) {
            OpsLoggers.HOST.warn(
                    "writeback_connection_unset userId={} activeConnections={} -- no default write-back "
                            + "connection; bookings will not be written to a calendar",
                    userId, active.size());
        }
        return designated;
    }

    /** The calendar within that connection which receives the event. */
    @Nullable
    private String writebackCalendarId(CalendarConnection connection) {
        return inventoryRepository.findByConnectionIdAndSelectedTrue(connection.getId())
                .filter(CalendarConnectionCalendar::isCanWrite)
                .filter(calendar -> availabilityPolicy.contributesToAvailability(connection, calendar))
                .map(CalendarConnectionCalendar::getExternalCalendarId)
                .orElse(null);
    }

    public record SchedulingProjection(
            CalendarProviderType provider,
            UUID connectionId,
            String calendarId) {
    }
}
