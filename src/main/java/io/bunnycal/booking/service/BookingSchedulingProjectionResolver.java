package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
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

    private Optional<CalendarConnection> resolveParticipantConnection(UUID participantUserId,
                                                                      @Nullable CalendarProviderType preferredProvider) {
        if (preferredProvider != null) {
            Optional<CalendarConnection> matching = connectionRepository
                    .findByUserIdAndProviderAndStatus(participantUserId, preferredProvider, CalendarConnectionStatus.ACTIVE);
            if (matching.isPresent()) {
                return matching;
            }
        }
        return connectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(participantUserId, CalendarConnectionStatus.ACTIVE)
                .stream()
                .findFirst();
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
