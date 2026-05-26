package io.bunnycal.sync.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.domain.ProviderEventProjection;
import io.bunnycal.calendar.repository.ProviderEventProjectionRepository;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.invariants.CompositeInvariantEvaluator;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import io.bunnycal.sync.repository.SyncReconcileInputSnapshotRepository;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.InternalRefType;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.bunnycal.sync.state.SyncJobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersistedReconcileSnapshotAssemblerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private ProviderEventProjectionRepository projectionRepository;
    @Mock private SyncReconcileInputSnapshotRepository snapshotRepository;

    private PersistedReconcileSnapshotAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new PersistedReconcileSnapshotAssembler(
                bookingRepository,
                projectionRepository,
                new CompositeSyncStateClassifier(),
                new PersistedSnapshotInvariantEvaluator(
                        new CompositeInvariantEvaluator(new CompositeSyncStateClassifier()),
                        new SimpleMeterRegistry()),
                new PersistedSnapshotCanonicalizer(),
                snapshotRepository,
                new SimpleMeterRegistry());
    }

    @Test
    void assembleAndPersist_enrichesRuntimeInputWithPersistedState() {
        CalendarSyncJob job = new CalendarSyncJob();
        job.setId(UUID.randomUUID());
        job.setInternalRefType(InternalRefType.BOOKING);
        job.setInternalRefId(UUID.randomUUID());
        job.setProvider("google");
        job.setDesiredAction(SyncDesiredAction.UPDATE);
        job.setStatus(SyncJobStatus.SYNCED);
        job.setExternalEventId("evt_20260514");

        BookingRepository.BookingStateRow row = new BookingRepository.BookingStateRow() {
            @Override public UUID getId() { return job.getInternalRefId(); }
            @Override public UUID getHostId() { return UUID.randomUUID(); }
            @Override public String getStatus() { return BookingState.CONFIRMED.name(); }
            @Override public Long getVersion() { return 1L; }
            @Override public Instant getExpiresAt() { return null; }
            @Override public Long getTerminalIntentEpoch() { return 7L; }
        };

        ProviderEventProjection projection = new ProviderEventProjection();
        projection.setProjectionStatus("ACTIVE");
        projection.setProjectionVersion(11L);
        projection.setConnectionId(UUID.randomUUID());
        projection.setProviderSequence(4L);
        projection.setProviderEtag("etag");
        projection.setProviderUpdatedAt(Instant.parse("2026-05-14T10:00:00Z"));

        when(bookingRepository.findStateById(job.getInternalRefId())).thenReturn(Optional.of(row));
        when(projectionRepository.findLatestByProviderAndExternalEventId("google", "evt_20260514"))
                .thenReturn(List.of(projection));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconcileInputSnapshot runtime = new ReconcileInputSnapshot(
                job.getId(), job.getInternalRefId(), "google", "evt_20260514",
                SyncJobStatus.SYNCED, SyncDesiredAction.UPDATE,
                CalendarService.ObserveEventStatus.EXISTS, null,
                null, null);

        PersistedReconcileSnapshotAssembler.SnapshotAssemblyResult result = assembler.assembleAndPersist(
                job,
                CalendarService.ObserveEventResult.exists(),
                runtime);

        assertNotNull(result.persistedSnapshot());
        assertEquals(11L, result.authoritativeInput().projectionVersion());
        assertEquals(7L, result.authoritativeInput().terminalIntentEpoch());
        assertEquals(SnapshotInputParity.SNAPSHOT_ENRICHED, result.parity());
    }
}
