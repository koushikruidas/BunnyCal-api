package io.bunnycal.sync;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.booking.repository.CalendarEventMappingRepository;
import io.bunnycal.booking.repository.CalendarEventMappingRepository.ClaimOutcome;
import io.bunnycal.booking.repository.CalendarEventMappingRepository.FinalizeOutcome;
import io.bunnycal.booking.repository.CalendarEventMappingRepository.MappingState;
import io.bunnycal.booking.repository.CalendarEventMappingRepository.TransitionOutcome;
import io.bunnycal.calendar.service.CalendarService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncWorkerTest {

    @Mock
    private CalendarEventMappingRepository repository;
    @Mock
    private FencingTokenGenerator tokenGenerator;
    @Mock
    private CalendarService calendarService;

    private SyncWorker worker;
    private SimpleMeterRegistry meterRegistry;

    private final UUID bookingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID participantUserId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        worker = new SyncWorker(repository, tokenGenerator, calendarService, meterRegistry);
    }

    @Test
    void notClaimed_exitsBeforeProviderCall() {
        when(tokenGenerator.nextToken()).thenReturn(10L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(10L), anyString()))
                .thenReturn(ClaimOutcome.REJECTED);

        worker.processBookingSync(bookingId, "google", participantUserId);

        verify(calendarService, never()).createEvent(any());
        verify(repository, never()).updateMappingWithEventId(any(), anyString(), any(), anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    void createdState_exitsWithoutProviderCall() {
        when(tokenGenerator.nextToken()).thenReturn(11L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(11L), anyString()))
                .thenReturn(ClaimOutcome.CLAIMED);
        when(repository.findMappingState(bookingId, "google", participantUserId))
                .thenReturn(Optional.of(new MappingState("CREATED", "ext-1", null, null, 11L, "w", 0, Instant.now())));

        worker.processBookingSync(bookingId, "google", participantUserId);

        verify(calendarService, never()).createEvent(any());
    }

    @Test
    void externalEventAlreadyPresent_exitsWithoutProviderCall() {
        when(tokenGenerator.nextToken()).thenReturn(12L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(12L), anyString()))
                .thenReturn(ClaimOutcome.CLAIMED);
        when(repository.findMappingState(bookingId, "google", participantUserId))
                .thenReturn(Optional.of(new MappingState("CLAIMED", "ext-1", null, null, 12L, "w", 0, Instant.now())));

        worker.processBookingSync(bookingId, "google", participantUserId);

        verify(calendarService, never()).createEvent(any());
    }

    @Test
    void successfulClaim_callsProvider_thenFinalizesWithSameTokenAndWorker() {
        AtomicReference<String> workerRef = new AtomicReference<>();
        when(tokenGenerator.nextToken()).thenReturn(13L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(13L), anyString()))
                .thenAnswer(invocation -> {
                    workerRef.set(invocation.getArgument(4));
                    return ClaimOutcome.CLAIMED;
                });
        when(repository.findMappingState(bookingId, "google", participantUserId))
                .thenAnswer(invocation -> Optional.of(new MappingState("CLAIMED", null, null, null, 13L, workerRef.get(), 0, Instant.now())));
        when(calendarService.createEvent(any())).thenReturn(CalendarService.CreateEventResult.success("ext-13"));
        when(repository.updateMappingWithEventId(eq(bookingId), eq("google"), eq(participantUserId), eq("ext-13"), any(), any(), eq(13L), anyString()))
                .thenReturn(FinalizeOutcome.SUCCESS);

        worker.processBookingSync(bookingId, "google", participantUserId);

        ArgumentCaptor<String> claimWorker = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finalizeWorker = ArgumentCaptor.forClass(String.class);
        verify(repository).claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(13L), claimWorker.capture());
        verify(repository).updateMappingWithEventId(eq(bookingId), eq("google"), eq(participantUserId), eq("ext-13"), any(), any(), eq(13L),
                finalizeWorker.capture());
        verify(calendarService).createEvent(any(CalendarService.CreateCalendarEventCommand.class));
        org.junit.jupiter.api.Assertions.assertEquals(claimWorker.getValue(), finalizeWorker.getValue());
    }

    @Test
    void splitBrainDetected_throws() {
        AtomicReference<String> workerRef = new AtomicReference<>();
        when(tokenGenerator.nextToken()).thenReturn(14L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(14L), anyString()))
                .thenAnswer(invocation -> {
                    workerRef.set(invocation.getArgument(4));
                    return ClaimOutcome.CLAIMED;
                });
        when(repository.findMappingState(bookingId, "google", participantUserId))
                .thenAnswer(invocation -> Optional.of(new MappingState("CLAIMED", null, null, null, 14L, workerRef.get(), 0, Instant.now())));
        when(calendarService.createEvent(any())).thenReturn(CalendarService.CreateEventResult.success("ext-14"));
        when(repository.updateMappingWithEventId(eq(bookingId), eq("google"), eq(participantUserId), eq("ext-14"), any(), any(), eq(14L), anyString()))
                .thenReturn(FinalizeOutcome.SPLIT_BRAIN_DETECTED);

        assertThrows(IllegalStateException.class, () -> worker.processBookingSync(bookingId, "google", participantUserId));
    }

    @Test
    void providerFailure_marksFailed_andExits() {
        AtomicReference<String> workerRef = new AtomicReference<>();
        when(tokenGenerator.nextToken()).thenReturn(15L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(15L), anyString()))
                .thenAnswer(invocation -> {
                    workerRef.set(invocation.getArgument(4));
                    return ClaimOutcome.CLAIMED;
                });
        when(repository.findMappingState(bookingId, "google", participantUserId))
                .thenAnswer(invocation -> Optional.of(new MappingState("CLAIMED", null, null, null, 15L, workerRef.get(), 0, Instant.now())));
        when(calendarService.createEvent(any()))
                .thenReturn(CalendarService.CreateEventResult.retryable("HTTP_429"));
        when(repository.markFailed(eq(bookingId), eq("google"), eq(participantUserId), anyString(), eq("HTTP_429"), eq(15L), any()))
                .thenReturn(TransitionOutcome.UPDATED);

        worker.processBookingSync(bookingId, "google", participantUserId);

        verify(repository).markFailed(eq(bookingId), eq("google"), eq(participantUserId), eq(workerRef.get()), eq("HTTP_429"), eq(15L), any());
        verify(repository, never()).updateMappingWithEventId(any(), anyString(), any(), anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    void recheckOwnershipDrift_exitsWithoutProviderCall() {
        when(tokenGenerator.nextToken()).thenReturn(16L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(participantUserId), eq(16L), anyString()))
                .thenReturn(ClaimOutcome.CLAIMED);
        when(repository.findMappingState(bookingId, "google", participantUserId))
                .thenReturn(Optional.of(new MappingState("CLAIMED", null, null, null, 17L, "another-worker", 0, Instant.now())));

        worker.processBookingSync(bookingId, "google", participantUserId);

        verify(calendarService, never()).createEvent(any());
        verify(repository, never()).updateMappingWithEventId(any(), anyString(), any(), anyString(), any(), any(), anyLong(), anyString());
    }
}
