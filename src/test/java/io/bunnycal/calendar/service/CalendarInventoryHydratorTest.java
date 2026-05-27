package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.client.GoogleApiClient;
import io.bunnycal.calendar.client.MicrosoftApiClient;
import io.bunnycal.calendar.client.ProviderCalendarInventoryEntry;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarInventoryHydratorTest {

    @Mock private CalendarConnectionCalendarRepository inventoryRepository;
    @Mock private GoogleApiClient googleApiClient;
    @Mock private MicrosoftApiClient microsoftApiClient;
    @Mock private TokenRefresher tokenRefresher;

    private CalendarInventoryHydrator hydrator;

    @BeforeEach
    void setUp() {
        hydrator = new CalendarInventoryHydrator(
                inventoryRepository, googleApiClient, microsoftApiClient, tokenRefresher, new SimpleMeterRegistry());
    }

    @Test
    void persist_google_inventory_uses_externalCalendarId_exactly_from_provider_entry_id() {
        UUID connectionId = UUID.randomUUID();
        when(inventoryRepository.findByConnectionIdOrderByPrimaryDescExternalCalendarIdAsc(connectionId))
                .thenReturn(List.of());
        when(inventoryRepository.save(any(CalendarConnectionCalendar.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProviderCalendarInventoryEntry entry = new ProviderCalendarInventoryEntry(
                "family17130278116817796873@group.calendar.google.com",
                "Family",
                false,
                true,
                true,
                false
        );
        hydrator.persist(connectionId, CalendarProviderType.GOOGLE, List.of(entry));

        ArgumentCaptor<CalendarConnectionCalendar> captor = ArgumentCaptor.forClass(CalendarConnectionCalendar.class);
        verify(inventoryRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalCalendarId())
                .isEqualTo("family17130278116817796873@group.calendar.google.com");
    }
}
