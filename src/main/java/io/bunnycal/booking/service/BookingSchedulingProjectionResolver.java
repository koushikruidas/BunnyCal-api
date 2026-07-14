package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.logging.OpsLoggers;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class BookingSchedulingProjectionResolver {

    private final CalendarConnectionRepository connectionRepository;
    private final CalendarConnectionCalendarRepository inventoryRepository;

    public BookingSchedulingProjectionResolver(CalendarConnectionRepository connectionRepository,
                                              CalendarConnectionCalendarRepository inventoryRepository) {
        this.connectionRepository = connectionRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Resolves the calendar a booking is written to.
     *
     * <p>An explicit projection triple on the event type pins the destination — the host chose a
     * specific calendar for this event type. Its absence means "use my default", which is resolved
     * live against {@code booking.hostId}'s own connections. Both cases are legitimate:
     *
     * <ul>
     *   <li>ROUND_ROBIN never carries a triple — {@code hostId} is the assigned participant, only
     *       known at booking time, and the owner cannot pick a calendar inside someone else's
     *       provider account.</li>
     *   <li>Other kinds carry one only if the host was actually asked. A host with a single calendar
     *       is never shown the picker, so nothing is pinned and the default is used. Freezing the
     *       calendar we would have shown them leaves the event type pinned to a connection they
     *       never chose — and reconnecting an account mints a new connection id, so that pin can
     *       silently go stale.</li>
     * </ul>
     */
    @Nullable
    public SchedulingProjection resolve(Booking booking, EventType eventType) {
        if (eventType.getKind() != EventKind.ROUND_ROBIN
                && eventType.getProjectionProvider() != null
                && eventType.getProjectionConnectionId() != null
                && eventType.getProjectionCalendarId() != null
                && !eventType.getProjectionCalendarId().isBlank()) {
            return new SchedulingProjection(
                    eventType.getProjectionProvider(),
                    eventType.getProjectionConnectionId(),
                    eventType.getProjectionCalendarId().trim());
        }

        CalendarConnection connection = resolveParticipantConnection(booking.getHostId(), eventType.getProjectionProvider())
                .orElse(null);
        if (connection == null || connection.getProvider() == null) {
            return null;
        }
        String calendarId = resolveParticipantCalendarId(connection.getId(), eventType.getProjectionCalendarId(), connection.getProvider());
        if (calendarId == null || calendarId.isBlank()) {
            return null;
        }
        return new SchedulingProjection(connection.getProvider(), connection.getId(), calendarId);
    }

    /**
     * Picks which of a user's connections a booking with no pinned calendar is written back to.
     *
     * <p>Reached when the event type names no destination: always for round-robin (the host is the
     * assigned participant, known only at booking time), and for any other kind whose host was never
     * shown the picker or chose to use their default. With several connections the target has to be
     * chosen here. In preference order: the user's designated default write-back connection, then
     * the one that owns a selected (or primary) calendar so this agrees with
     * {@link #resolveParticipantCalendarId}, then the oldest. The last case is genuinely ambiguous
     * and is logged rather than silently resolved.
     */
    private Optional<CalendarConnection> resolveParticipantConnection(UUID participantUserId,
                                                                      @Nullable CalendarProviderType preferredProvider) {
        if (preferredProvider != null) {
            List<CalendarConnection> matching = connectionRepository.findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
                    participantUserId, preferredProvider, CalendarConnectionStatus.ACTIVE);
            if (!matching.isEmpty()) {
                return Optional.of(pickWritebackConnection(participantUserId, matching, preferredProvider));
            }
        }
        List<CalendarConnection> any = connectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(
                participantUserId, CalendarConnectionStatus.ACTIVE);
        if (any.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pickWritebackConnection(participantUserId, any, null));
    }

    private CalendarConnection pickWritebackConnection(UUID participantUserId,
                                                       List<CalendarConnection> candidates,
                                                       @Nullable CalendarProviderType preferredProvider) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        Optional<CalendarConnection> designated = candidates.stream()
                .filter(CalendarConnection::isDefaultWriteback)
                .findFirst();
        if (designated.isPresent()) {
            return designated.get();
        }
        Optional<CalendarConnection> withSelectedCalendar = candidates.stream()
                .filter(c -> inventoryRepository.findByConnectionIdAndSelectedTrue(c.getId()).isPresent())
                .findFirst();
        if (withSelectedCalendar.isPresent()) {
            return withSelectedCalendar.get();
        }
        CalendarConnection fallback = candidates.get(0);
        OpsLoggers.HOST.warn(
                "ambiguous_writeback_connection hostId={} provider={} candidateCount={} chosenConnectionId={} "
                        + "reason=no_default_and_no_selected_calendar",
                participantUserId, preferredProvider, candidates.size(), fallback.getId());
        return fallback;
    }

    @Nullable
    private String resolveParticipantCalendarId(UUID connectionId,
                                                @Nullable String preferredCalendarId,
                                                CalendarProviderType providerType) {
        if (preferredCalendarId != null && !preferredCalendarId.isBlank()) {
            String trimmed = preferredCalendarId.trim();
            if ("primary".equalsIgnoreCase(trimmed) && providerType == CalendarProviderType.GOOGLE) {
                return "primary";
            }
            if (inventoryRepository.findByConnectionIdAndExternalCalendarId(connectionId, trimmed).isPresent()) {
                return trimmed;
            }
        }

        return inventoryRepository.findByConnectionIdAndSelectedTrue(connectionId)
                .map(c -> c.getExternalCalendarId())
                .or(() -> {
                    List<io.bunnycal.calendar.domain.CalendarConnectionCalendar> calendars =
                            inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId);
                    return calendars.stream().findFirst().map(io.bunnycal.calendar.domain.CalendarConnectionCalendar::getExternalCalendarId);
                })
                .orElse(providerType == CalendarProviderType.GOOGLE ? "primary" : null);
    }

    public record SchedulingProjection(
            CalendarProviderType provider,
            UUID connectionId,
            String calendarId) {
    }
}
