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

    @Nullable
    public SchedulingProjection resolve(Booking booking, EventType eventType) {
        if (eventType.getKind() != EventKind.ROUND_ROBIN) {
            if (eventType.getProjectionProvider() == null
                    || eventType.getProjectionConnectionId() == null
                    || eventType.getProjectionCalendarId() == null
                    || eventType.getProjectionCalendarId().isBlank()) {
                return null;
            }
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
     * Picks which of a participant's connections a round-robin booking is written back to.
     *
     * <p>Round-robin has no per-event-type projection triple — the host is only known at booking
     * time — so with several connections per provider the target has to be chosen here. In
     * preference order: the participant's designated default write-back connection, then the one
     * that owns a selected (or primary) calendar so this agrees with
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
                "round_robin_ambiguous_writeback hostId={} provider={} candidateCount={} chosenConnectionId={} "
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
