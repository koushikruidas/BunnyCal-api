package io.bunnycal.conferencing.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resolves native conferencing against the exact calendar that receives booking projections. */
@Service
public class NativeConferencingCapabilityService {

    private final CalendarConnectionCalendarRepository calendarRepository;

    public NativeConferencingCapabilityService(CalendarConnectionCalendarRepository calendarRepository) {
        this.calendarRepository = calendarRepository;
    }

    public boolean canServe(CalendarConnection connection, ConferencingProviderType provider) {
        if (connection == null || provider == null) {
            return false;
        }
        if (!provider.requiresCalendarProvider()) {
            return true;
        }
        if (provider == ConferencingProviderType.GOOGLE_MEET) {
            return connection.getProvider() == CalendarProviderType.GOOGLE;
        }
        if (provider != ConferencingProviderType.MICROSOFT_TEAMS
                || connection.getProvider() != CalendarProviderType.MICROSOFT
                || MicrosoftAccountClassifier.isConsumerMsa(connection)) {
            return false;
        }
        return projectionCalendar(connection.getId())
                .map(CalendarConnectionCalendar::isSupportsNativeTeams)
                .orElse(false);
    }

    public boolean calendarSupportsTeams(UUID connectionId, String externalCalendarId) {
        if (connectionId == null || externalCalendarId == null || externalCalendarId.isBlank()) {
            return false;
        }
        return calendarRepository.findByConnectionIdAndExternalCalendarId(connectionId, externalCalendarId)
                .map(CalendarConnectionCalendar::isSupportsNativeTeams)
                .orElse(false);
    }

    private java.util.Optional<CalendarConnectionCalendar> projectionCalendar(UUID connectionId) {
        if (connectionId == null) {
            return java.util.Optional.empty();
        }
        java.util.Optional<CalendarConnectionCalendar> selected =
                calendarRepository.findByConnectionIdAndSelectedTrue(connectionId);
        if (selected.isPresent()) {
            return selected;
        }
        return calendarRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId)
                .stream()
                .filter(CalendarConnectionCalendar::isPrimary)
                .findFirst();
    }
}
