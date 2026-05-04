package com.daedalussystems.easySchedule.sync;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.ClaimOutcome;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.FinalizeOutcome;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.MappingState;
import com.daedalussystems.easySchedule.booking.repository.CalendarEventMappingRepository.TransitionOutcome;
import com.daedalussystems.easySchedule.sync.CalendarProviderClient;
import com.daedalussystems.easySchedule.sync.FencingTokenGenerator;
import com.daedalussystems.easySchedule.sync.SyncWorker;
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
    private CalendarProviderClient providerClient;

    private SyncWorker worker;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        worker = new SyncWorker(repository, tokenGenerator, providerClient, meterRegistry);
    }

    @Test
    void notClaimed_exitsBeforeProviderCall() {
        UUID bookingId = UUID.randomUUID();
        when(tokenGenerator.nextToken()).thenReturn(10L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(10L), anyString()))
                .thenReturn(ClaimOutcome.REJECTED);

        worker.processBookingSync(bookingId, "google");

        verify(providerClient, never()).createEvent(any(), anyString(), anyString());
        verify(repository, never()).updateMappingWithEventId(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void createdState_exitsWithoutProviderCall() {
        UUID bookingId = UUID.randomUUID();
        when(tokenGenerator.nextToken()).thenReturn(11L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(11L), anyString()))
                .thenReturn(ClaimOutcome.CLAIMED);
        when(repository.findMappingState(bookingId, "google"))
                .thenReturn(Optional.of(new MappingState("CREATED", "ext-1", 11L, "w", 0, Instant.now())));

        worker.processBookingSync(bookingId, "google");

        verify(providerClient, never()).createEvent(any(), anyString(), anyString());
    }

    @Test
    void externalEventAlreadyPresent_exitsWithoutProviderCall() {
        UUID bookingId = UUID.randomUUID();
        when(tokenGenerator.nextToken()).thenReturn(12L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(12L), anyString()))
                .thenReturn(ClaimOutcome.CLAIMED);
        when(repository.findMappingState(bookingId, "google"))
                .thenReturn(Optional.of(new MappingState("CLAIMED", "ext-1", 12L, "w", 0, Instant.now())));

        worker.processBookingSync(bookingId, "google");

        verify(providerClient, never()).createEvent(any(), anyString(), anyString());
    }

    @Test
    void successfulClaim_callsProvider_thenFinalizesWithSameTokenAndWorker() {
        UUID bookingId = UUID.randomUUID();
        AtomicReference<String> workerRef = new AtomicReference<>();
        when(tokenGenerator.nextToken()).thenReturn(13L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(13L), anyString()))
                .thenAnswer(invocation -> {
                    workerRef.set(invocation.getArgument(3));
                    return ClaimOutcome.CLAIMED;
                });
        when(repository.findMappingState(bookingId, "google"))
                .thenAnswer(invocation -> Optional.of(new MappingState("CLAIMED", null, 13L, workerRef.get(), 0, Instant.now())));
        when(providerClient.createEvent(eq(bookingId), eq("google"), anyString())).thenReturn("ext-13");
        when(repository.updateMappingWithEventId(eq(bookingId), eq("google"), eq("ext-13"), eq(13L), anyString()))
                .thenReturn(FinalizeOutcome.SUCCESS);

        worker.processBookingSync(bookingId, "google");

        ArgumentCaptor<String> claimWorker = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finalizeWorker = ArgumentCaptor.forClass(String.class);
        verify(repository).claimBookingForSync(eq(bookingId), eq("google"), eq(13L), claimWorker.capture());
        verify(repository).updateMappingWithEventId(eq(bookingId), eq("google"), eq("ext-13"), eq(13L),
                finalizeWorker.capture());
        verify(providerClient).createEvent(eq(bookingId), eq("google"), eq("google:" + bookingId));
        org.junit.jupiter.api.Assertions.assertEquals(claimWorker.getValue(), finalizeWorker.getValue());
    }

    @Test
    void splitBrainDetected_throws() {
        UUID bookingId = UUID.randomUUID();
        AtomicReference<String> workerRef = new AtomicReference<>();
        when(tokenGenerator.nextToken()).thenReturn(14L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(14L), anyString()))
                .thenAnswer(invocation -> {
                    workerRef.set(invocation.getArgument(3));
                    return ClaimOutcome.CLAIMED;
                });
        when(repository.findMappingState(bookingId, "google"))
                .thenAnswer(invocation -> Optional.of(new MappingState("CLAIMED", null, 14L, workerRef.get(), 0, Instant.now())));
        when(providerClient.createEvent(eq(bookingId), eq("google"), anyString())).thenReturn("ext-14");
        when(repository.updateMappingWithEventId(eq(bookingId), eq("google"), eq("ext-14"), eq(14L), anyString()))
                .thenReturn(FinalizeOutcome.SPLIT_BRAIN_DETECTED);

        assertThrows(IllegalStateException.class, () -> worker.processBookingSync(bookingId, "google"));
    }

    @Test
    void providerFailure_marksFailed_andExits() {
        UUID bookingId = UUID.randomUUID();
        AtomicReference<String> workerRef = new AtomicReference<>();
        when(tokenGenerator.nextToken()).thenReturn(15L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(15L), anyString()))
                .thenAnswer(invocation -> {
                    workerRef.set(invocation.getArgument(3));
                    return ClaimOutcome.CLAIMED;
                });
        when(repository.findMappingState(bookingId, "google"))
                .thenAnswer(invocation -> Optional.of(new MappingState("CLAIMED", null, 15L, workerRef.get(), 0, Instant.now())));
        when(providerClient.createEvent(eq(bookingId), eq("google"), anyString()))
                .thenThrow(new RuntimeException("provider timeout"));
        when(repository.markFailed(eq(bookingId), eq("google"), anyString(), eq("provider timeout"), eq(15L), any()))
                .thenReturn(TransitionOutcome.UPDATED);

        worker.processBookingSync(bookingId, "google");

        verify(repository).markFailed(eq(bookingId), eq("google"), eq(workerRef.get()), eq("provider timeout"), eq(15L), any());
        verify(repository, never()).updateMappingWithEventId(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void recheckOwnershipDrift_exitsWithoutProviderCall() {
        UUID bookingId = UUID.randomUUID();
        when(tokenGenerator.nextToken()).thenReturn(16L);
        when(repository.claimBookingForSync(eq(bookingId), eq("google"), eq(16L), anyString()))
                .thenReturn(ClaimOutcome.CLAIMED);
        when(repository.findMappingState(bookingId, "google"))
                .thenReturn(Optional.of(new MappingState("CLAIMED", null, 17L, "another-worker", 0, Instant.now())));

        worker.processBookingSync(bookingId, "google");

        verify(providerClient, never()).createEvent(any(), anyString(), anyString());
        verify(repository, never()).updateMappingWithEventId(any(), anyString(), anyString(), anyLong(), anyString());
    }
}
