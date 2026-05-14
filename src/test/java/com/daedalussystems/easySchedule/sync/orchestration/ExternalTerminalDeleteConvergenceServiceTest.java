package com.daedalussystems.easySchedule.sync.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ExternalTerminalDeleteConvergenceServiceTest {
    @Mock private CalendarSyncJobRepository syncJobRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private SlotCacheVersionService slotCacheVersionService;
    @Mock private OutboxPublisher outboxPublisher;

    private ExternalTerminalDeleteConvergenceService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ExternalTerminalDeleteConvergenceService(
                syncJobRepository,
                bookingRepository,
                slotCacheVersionService,
                outboxPublisher,
                new SimpleMeterRegistry());
    }

    @Test
    void syncedJobTerminalDelete_projectsBookingAndReleasesSlotCache() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        CalendarSyncJob job = job(bookingId, "ext-1", 4L);
        when(syncJobRepository.markSyncedLifecycle(job.getId(), 4L, "ext-1", "TERMINAL_EXTERNAL_DELETE"))
                .thenReturn(1);
        when(bookingRepository.findProjectionStateById(bookingId))
                .thenReturn(Optional.of(row(bookingId, hostId, "CONFIRMED", null)))
                .thenReturn(Optional.of(row(bookingId, hostId, "CANCELLED", Instant.parse("2026-05-14T00:00:00Z"))));
        when(bookingRepository.projectExternalTerminalToCancelled(bookingId)).thenReturn(1);

        var result = service.convergeSyncedJob(job, "reconcile");

        assertEquals(1, result.lifecycleRows());
        assertEquals(1, result.bookingRows());
        assertEquals("applied", result.result());
        verify(bookingRepository).projectExternalTerminalToCancelled(bookingId);
        verify(slotCacheVersionService).bumpVersion(hostId);
        verify(outboxPublisher).publish(eq("Booking"), eq(bookingId), any());
    }

    @Test
    void providerTombstoneReplayNoopsWhenBookingAlreadyTerminal() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        when(syncJobRepository.markLifecycleByBookingProviderExternalEvent(
                "BOOKING", bookingId, "google", "ext-1", "TERMINAL_EXTERNAL_DELETE"))
                .thenReturn(0);
        when(bookingRepository.findProjectionStateById(bookingId))
                .thenReturn(Optional.of(row(bookingId, hostId, "CANCELLED", Instant.parse("2026-05-14T00:00:00Z"))));

        var result = service.convergeProviderTombstone(bookingId, "google", "ext-1", "provider_projection");

        assertEquals(0, result.lifecycleRows());
        assertEquals(0, result.bookingRows());
        assertEquals("already_terminal", result.result());
        verify(bookingRepository, never()).projectExternalTerminalToCancelled(bookingId);
        verify(slotCacheVersionService, never()).bumpVersion(any());
        verify(outboxPublisher, never()).publish(any(), any(), any());
    }

    private static CalendarSyncJob job(UUID bookingId, String externalEventId, long version) {
        CalendarSyncJob job = new CalendarSyncJob();
        job.setId(UUID.randomUUID());
        job.setInternalRefType(InternalRefType.BOOKING);
        job.setInternalRefId(bookingId);
        job.setProvider("google");
        job.setDesiredAction(SyncDesiredAction.UPDATE);
        job.setStatus(SyncJobStatus.SYNCED);
        job.setExternalEventId(externalEventId);
        job.setVersion(version);
        return job;
    }

    private static BookingRepository.BookingProjectionRow row(UUID bookingId,
                                                              UUID hostId,
                                                              String status,
                                                              Instant releasedAt) {
        return new BookingRepository.BookingProjectionRow() {
            public UUID getId() { return bookingId; }
            public UUID getHostId() { return hostId; }
            public String getStatus() { return status; }
            public Instant getAvailabilityReleasedAt() { return releasedAt; }
        };
    }
}
