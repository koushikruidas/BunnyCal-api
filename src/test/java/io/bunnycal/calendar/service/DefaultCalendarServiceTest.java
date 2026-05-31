package io.bunnycal.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.calendar.client.CalendarProviderClient;
import io.bunnycal.calendar.client.CalendarProviderClientRegistry;
import io.bunnycal.calendar.domain.CalendarOperationStatus;
import io.bunnycal.calendar.domain.CalendarProviderOperation;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarProviderOperationRepository;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultCalendarServiceTest {
    @Mock
    private CalendarProviderClient providerClient;
    @Mock
    private CalendarProviderOperationRepository operationRepository;

    private DefaultCalendarService service;

    @BeforeEach
    void setUp() {
        lenient().when(providerClient.providerType()).thenReturn(CalendarProviderType.GOOGLE);
        CalendarProviderClientRegistry registry = new CalendarProviderClientRegistry(List.of(providerClient));
        service = new DefaultCalendarService(registry, operationRepository);
    }

    @Test
    void createEvent_firstAttemptExecutesProviderCall() {
        UUID bookingId = UUID.randomUUID();
        String idempotencyKey = "google:" + bookingId;
        CalendarProviderOperation inserted = new CalendarProviderOperation();
        inserted.setProvider(CalendarProviderType.GOOGLE);
        inserted.setConnectionId(bookingId);
        inserted.setIdempotencyKey(idempotencyKey);
        inserted.setStatus(CalendarOperationStatus.CREATING);

        when(operationRepository.findByProviderAndIdempotencyKey(CalendarProviderType.GOOGLE, idempotencyKey))
                .thenReturn(Optional.empty(), Optional.of(inserted));
        when(operationRepository.save(org.mockito.ArgumentMatchers.any(CalendarProviderOperation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.createEvent(bookingId, "google", idempotencyKey, ConferencingInstruction.none(), null))
                .thenReturn(new CalendarProviderClient.CreateEventDetails("ext-1", "https://calendar.google.com/event?eid=1", "https://meet.google.com/1"));

        CalendarService.CreateEventResult result = service.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, "google", idempotencyKey));

        assertEquals(CalendarService.CreateEventStatus.SUCCESS, result.status());
        assertEquals("ext-1", result.externalEventId());
        assertEquals("https://calendar.google.com/event?eid=1", result.providerEventUrl());
        assertEquals("https://meet.google.com/1", result.conferenceUrl());
        verify(providerClient).createEvent(bookingId, "google", idempotencyKey, ConferencingInstruction.none(), null);
    }

    @Test
    void createEvent_existingFreshCreatingReturnsInProgress() {
        UUID bookingId = UUID.randomUUID();
        String idempotencyKey = "google:" + bookingId;
        CalendarProviderOperation existing = new CalendarProviderOperation();
        existing.setProvider(CalendarProviderType.GOOGLE);
        existing.setConnectionId(bookingId);
        existing.setIdempotencyKey(idempotencyKey);
        existing.setStatus(CalendarOperationStatus.CREATING);
        existing.setCreatedAt(java.time.Instant.now());

        when(operationRepository.findByProviderAndIdempotencyKey(CalendarProviderType.GOOGLE, idempotencyKey))
                .thenReturn(Optional.of(existing));

        CalendarService.CreateEventResult result = service.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, "google", idempotencyKey));

        assertEquals(CalendarService.CreateEventStatus.RETRYABLE_FAILURE, result.status());
        assertEquals("IN_PROGRESS", result.errorCode());
        verify(providerClient, never()).createEvent(bookingId, "google", idempotencyKey, ConferencingInstruction.none(), null);
    }

    @Test
    void createEvent_existingStaleCreatingTakesOverAndExecutesProviderCall() {
        UUID bookingId = UUID.randomUUID();
        String idempotencyKey = "google:" + bookingId;
        CalendarProviderOperation existing = new CalendarProviderOperation();
        existing.setProvider(CalendarProviderType.GOOGLE);
        existing.setConnectionId(bookingId);
        existing.setIdempotencyKey(idempotencyKey);
        existing.setStatus(CalendarOperationStatus.CREATING);
        existing.setLastAttemptAt(Instant.now().minusSeconds(90));

        when(operationRepository.findByProviderAndIdempotencyKey(CalendarProviderType.GOOGLE, idempotencyKey))
                .thenReturn(Optional.of(existing));
        when(operationRepository.save(org.mockito.ArgumentMatchers.any(CalendarProviderOperation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(providerClient.createEvent(bookingId, "google", idempotencyKey, ConferencingInstruction.none(), null))
                .thenReturn(new CalendarProviderClient.CreateEventDetails("ext-2", null, null));

        CalendarService.CreateEventResult result = service.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, "google", idempotencyKey));

        assertEquals(CalendarService.CreateEventStatus.SUCCESS, result.status());
        assertEquals("ext-2", result.externalEventId());
        verify(providerClient).createEvent(bookingId, "google", idempotencyKey, ConferencingInstruction.none(), null);
    }
}
