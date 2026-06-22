package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeOrchestrationJsonCodec;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.sync.state.SyncSourceAttribution;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderCalendarSelectionServiceTest {

    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private CalendarConnectionCalendarRepository calendarConnectionCalendarRepository;

    private ProviderCalendarSelectionService service;

    @BeforeEach
    void setUp() {
        service = new ProviderCalendarSelectionService(
                eventTypeRepository, new EventTypeOrchestrationJsonCodec(new ObjectMapper()),
                calendarConnectionCalendarRepository);
    }

    @Test
    void selection_hydrates_from_availability_and_projection_and_deduplicates() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.MICROSOFT);

        EventType a = new EventType();
        a.setId(UUID.randomUUID());
        a.setUserId(userId);
        a.setAvailabilityCalendarsJson("""
                [{"connectionId":"%s","provider":"microsoft","externalCalendarId":"AQMk-work"}]
                """.formatted(connectionId));
        a.setProjectionConnectionId(connectionId);
        a.setProjectionCalendarId("AQMk-projection");

        EventType b = new EventType();
        b.setId(UUID.randomUUID());
        b.setUserId(userId);
        b.setAvailabilityCalendarsJson("""
                [{"connectionId":"%s","provider":"microsoft","externalCalendarId":"AQMk-work"},
                 {"connectionId":"%s","provider":"microsoft","externalCalendarId":"AQMk-family"}]
                """.formatted(connectionId, connectionId));
        b.setProjectionConnectionId(connectionId);
        b.setProjectionCalendarId("AQMk-projection");

        when(eventTypeRepository.findByUserIdAndDeletedAtIsNullOrderByNameAsc(userId)).thenReturn(List.of(a, b));

        Set<String> selected = service.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC);

        assertThat(selected).containsExactly("AQMk-work", "AQMk-projection", "AQMk-family");
    }

    @Test
    void selection_rejects_uuid_shaped_or_connection_id_corruption() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.GOOGLE);

        EventType bad = new EventType();
        bad.setId(UUID.randomUUID());
        bad.setUserId(userId);
        bad.setAvailabilityCalendarsJson("""
                [{"connectionId":"%s","provider":"google","externalCalendarId":"%s"},
                 {"connectionId":"%s","provider":"google","externalCalendarId":"%s"}]
                """.formatted(connectionId, connectionId, connectionId, UUID.randomUUID()));
        bad.setProjectionConnectionId(connectionId);
        bad.setProjectionCalendarId(UUID.randomUUID().toString());

        when(eventTypeRepository.findByUserIdAndDeletedAtIsNullOrderByNameAsc(userId)).thenReturn(List.of(bad));

        Set<String> selected = service.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC);

        assertThat(selected).isEmpty();
    }

    @Test
    void selection_rejects_microsoft_primary_alias() {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        CalendarConnection connection = connection(connectionId, userId, CalendarProviderType.MICROSOFT);

        EventType et = new EventType();
        et.setId(UUID.randomUUID());
        et.setUserId(userId);
        et.setAvailabilityCalendarsJson("""
                [{"connectionId":"%s","provider":"microsoft","externalCalendarId":"primary"}]
                """.formatted(connectionId));
        et.setProjectionConnectionId(connectionId);
        et.setProjectionCalendarId("primary");

        when(eventTypeRepository.findByUserIdAndDeletedAtIsNullOrderByNameAsc(userId)).thenReturn(List.of(et));

        Set<String> selected = service.selectedAvailabilityCalendarIds(connection, SyncSourceAttribution.PULL_SYNC);

        assertThat(selected).isEmpty();
    }

    private static CalendarConnection connection(UUID id, UUID userId, CalendarProviderType provider) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(userId);
        c.setProvider(provider);
        try {
            Field f = CalendarConnection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return c;
    }
}
