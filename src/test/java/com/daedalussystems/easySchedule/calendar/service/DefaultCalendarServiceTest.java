package com.daedalussystems.easySchedule.calendar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.client.CalendarProviderClient;
import com.daedalussystems.easySchedule.calendar.domain.CalendarOperationStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderOperation;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarProviderOperationRepository;
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
        service = new DefaultCalendarService(providerClient, operationRepository);
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
        when(providerClient.createEvent(bookingId, "google", idempotencyKey))
                .thenReturn(new CalendarProviderClient.CreateEventDetails("ext-1", "https://calendar.google.com/event?eid=1", "https://meet.google.com/1"));

        CalendarService.CreateEventResult result = service.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, "google", idempotencyKey));

        assertEquals(CalendarService.CreateEventStatus.SUCCESS, result.status());
        assertEquals("ext-1", result.externalEventId());
        assertEquals("https://calendar.google.com/event?eid=1", result.providerEventUrl());
        assertEquals("https://meet.google.com/1", result.conferenceUrl());
        verify(providerClient).createEvent(bookingId, "google", idempotencyKey);
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
        verify(providerClient, never()).createEvent(bookingId, "google", idempotencyKey);
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
        when(providerClient.createEvent(bookingId, "google", idempotencyKey))
                .thenReturn(new CalendarProviderClient.CreateEventDetails("ext-2", null, null));

        CalendarService.CreateEventResult result = service.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, "google", idempotencyKey));

        assertEquals(CalendarService.CreateEventStatus.SUCCESS, result.status());
        assertEquals("ext-2", result.externalEventId());
        verify(providerClient).createEvent(bookingId, "google", idempotencyKey);
    }
}
