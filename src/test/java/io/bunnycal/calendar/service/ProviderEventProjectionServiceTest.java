package io.bunnycal.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.booking.repository.CalendarEventMappingRepository;
import io.bunnycal.calendar.domain.ProviderEventProjection;
import io.bunnycal.calendar.domain.ProviderEventProjectionStatus;
import io.bunnycal.calendar.repository.ProviderEventProjectionRepository;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.orchestration.ExternalTerminalDeleteConvergenceService;
import io.bunnycal.sync.orchestration.ExternalUpdateConvergenceService;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ProviderEventProjectionServiceTest {

    @Mock private ProviderEventProjectionRepository repository;
    @Mock private ProviderEventVersionComparator comparator;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private SyncInvariantMonitor invariantMonitor;
    @Mock private CalendarEventMappingRepository mappingRepository;
    @Mock private CalendarSyncJobRepository syncJobRepository;
    @Mock private ExternalTerminalDeleteConvergenceService terminalDeleteConvergenceService;
    @Mock private ExternalUpdateConvergenceService externalUpdateConvergenceService;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        TransactionStatus txStatus = new DefaultTransactionStatus("test", null, true, false, false, false, false, null);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    private ProviderEventProjectionService service(boolean acceptAmbiguous) {
        return new ProviderEventProjectionService(
                repository,
                comparator,
                meterRegistry,
                transactionManager,
                invariantMonitor,
                mappingRepository,
                syncJobRepository,
                terminalDeleteConvergenceService,
                externalUpdateConvergenceService,
                acceptAmbiguous);
    }

    private static CalendarEventIngestionService.IncomingCalendarEvent tombstone(String externalEventId) {
        // Microsoft @removed payload shape: id only, no version metadata.
        // CalendarEventIngestionService's validate() requires a non-null start before end,
        // so MicrosoftIncrementalSyncObservationClient stuffs EPOCH; the projection layer
        // doesn't care about start/end at all for terminal observations.
        return new CalendarEventIngestionService.IncomingCalendarEvent(
                externalEventId,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60),
                true,   // cancelled
                true,   // deleted
                null,   // providerSequence
                null,   // providerUpdatedAt
                null,   // providerEtag
                "tombstone-payload-hash",
                "AAMkAGI...",
                null, null, null);
    }

    private static ProviderEventProjection activeProjection(UUID connectionId, String externalEventId) {
        ProviderEventProjection projection = new ProviderEventProjection();
        try {
            Field idField = ProviderEventProjection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(projection, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        projection.setConnectionId(connectionId);
        projection.setProvider("MICROSOFT");
        projection.setExternalEventId(externalEventId);
        projection.setProjectionStatus(ProviderEventProjectionStatus.ACTIVE.name());
        projection.setProjectionVersion(1L);
        projection.setProviderEtag("real-changekey");
        projection.setPayloadHash("real-payload-hash");
        projection.setProviderUpdatedAt(Instant.parse("2026-05-28T06:55:50Z"));
        projection.setLastObservedAt(Instant.parse("2026-05-28T06:55:50Z"));
        return projection;
    }

    @Test
    void terminalTombstoneAgainstActive_forceAppliedDespiteAmbiguousDisabled() {
        UUID connectionId = UUID.randomUUID();
        String externalEventId = "AQ...AAPVPpoAAAA=";
        ProviderEventProjection existing = activeProjection(connectionId, externalEventId);

        when(repository.findWithLockByConnectionIdAndProviderAndExternalEventId(
                eq(connectionId), eq("MICROSOFT"), eq(externalEventId)))
                .thenReturn(Optional.of(existing));
        when(mappingRepository.findUniqueBookingForProviderEvent(any(), anyString(), anyString()))
                .thenReturn(new CalendarEventMappingRepository.BookingLinkageResult(Optional.empty(), "no_match", 0));
        when(syncJobRepository.findBookingCandidatesForExternalEvent(any(), anyString(), anyString()))
                .thenReturn(java.util.List.of());
        // The comparator returns AMBIGUOUS, which under acceptAmbiguous=false would normally reject.
        when(comparator.compare(any(), any()))
                .thenReturn(ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT);
        when(repository.saveAndFlush(any(ProviderEventProjection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProviderEventProjectionService svc = service(false);

        boolean applied = svc.shouldApplyAndAdvance(connectionId, "MICROSOFT", tombstone(externalEventId));

        assertThat(applied).isTrue();

        ArgumentCaptor<ProviderEventProjection> captor = ArgumentCaptor.forClass(ProviderEventProjection.class);
        verify(repository).saveAndFlush(captor.capture());
        ProviderEventProjection saved = captor.getValue();
        // Tombstone-of-deleted should land at TOMBSTONED_HARD (incoming.deleted()=true).
        assertThat(saved.getProjectionStatus()).isEqualTo(ProviderEventProjectionStatus.TOMBSTONED_HARD.name());
        assertThat(saved.getProjectionVersion()).isEqualTo(2L);

        assertThat(meterRegistry.counter("sync.projection.terminal_tombstone_forced.total",
                "provider", "MICROSOFT", "reason", "deleted").count()).isEqualTo(1.0);
    }

    @Test
    void cancelledOnlyAgainstActive_landsAsTombstonedSoftEvenWhenAmbiguousDisabled() {
        UUID connectionId = UUID.randomUUID();
        String externalEventId = "MS-EVT-CANCELLED-ONLY";
        ProviderEventProjection existing = activeProjection(connectionId, externalEventId);

        when(repository.findWithLockByConnectionIdAndProviderAndExternalEventId(
                eq(connectionId), eq("MICROSOFT"), eq(externalEventId)))
                .thenReturn(Optional.of(existing));
        when(mappingRepository.findUniqueBookingForProviderEvent(any(), anyString(), anyString()))
                .thenReturn(new CalendarEventMappingRepository.BookingLinkageResult(Optional.empty(), "no_match", 0));
        when(syncJobRepository.findBookingCandidatesForExternalEvent(any(), anyString(), anyString()))
                .thenReturn(java.util.List.of());
        when(comparator.compare(any(), any()))
                .thenReturn(ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT);
        when(repository.saveAndFlush(any(ProviderEventProjection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // cancelled=true, deleted=false — typical isCancelled outcome (rare for MS @removed but
        // possible via isCancelled flag on a non-removed payload).
        CalendarEventIngestionService.IncomingCalendarEvent incoming =
                new CalendarEventIngestionService.IncomingCalendarEvent(
                        externalEventId,
                        Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                        true,   // cancelled
                        false,  // deleted
                        null, null, null, "tombstone-hash",
                        "calendar-id", null, null, null);

        boolean applied = service(false).shouldApplyAndAdvance(connectionId, "MICROSOFT", incoming);

        assertThat(applied).isTrue();
        ArgumentCaptor<ProviderEventProjection> captor = ArgumentCaptor.forClass(ProviderEventProjection.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getProjectionStatus())
                .isEqualTo(ProviderEventProjectionStatus.TOMBSTONED_SOFT.name());
    }

    @Test
    void duplicateTombstoneAgainstAlreadyTombstoned_doesNotForceReapply() {
        UUID connectionId = UUID.randomUUID();
        String externalEventId = "MS-EVT-ALREADY-TOMBSTONED";
        ProviderEventProjection existing = activeProjection(connectionId, externalEventId);
        existing.setProjectionStatus(ProviderEventProjectionStatus.TOMBSTONED_HARD.name());
        // Match the hash so projection_echo suppression kicks in for the duplicate.
        existing.setPayloadHash("tombstone-payload-hash");
        existing.setBookingId(UUID.randomUUID()); // echo guard requires a linked booking

        when(repository.findWithLockByConnectionIdAndProviderAndExternalEventId(
                eq(connectionId), eq("MICROSOFT"), eq(externalEventId)))
                .thenReturn(Optional.of(existing));

        boolean applied = service(false).shouldApplyAndAdvance(connectionId, "MICROSOFT", tombstone(externalEventId));

        assertThat(applied).isFalse();
        verify(repository, never()).saveAndFlush(any(ProviderEventProjection.class));
        // Force-apply counter must NOT increment — the row is already terminal.
        assertThat(meterRegistry.find("sync.projection.terminal_tombstone_forced.total").counter())
                .satisfiesAnyOf(
                        c -> assertThat(c).isNull(),
                        c -> assertThat(c.count()).isEqualTo(0.0));
    }

    @Test
    void activeUpdateUnderAmbiguousDisabled_stillRejected_shortcutScopedToTombstones() {
        UUID connectionId = UUID.randomUUID();
        String externalEventId = "MS-EVT-ACTIVE-AMBIGUOUS";
        ProviderEventProjection existing = activeProjection(connectionId, externalEventId);

        when(repository.findWithLockByConnectionIdAndProviderAndExternalEventId(
                eq(connectionId), eq("MICROSOFT"), eq(externalEventId)))
                .thenReturn(Optional.of(existing));
        when(mappingRepository.findUniqueBookingForProviderEvent(any(), anyString(), anyString()))
                .thenReturn(new CalendarEventMappingRepository.BookingLinkageResult(Optional.empty(), "no_match", 0));
        when(syncJobRepository.findBookingCandidatesForExternalEvent(any(), anyString(), anyString()))
                .thenReturn(java.util.List.of());
        when(comparator.compare(any(), any()))
                .thenReturn(ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT);

        // Non-terminal observation (cancelled=false, deleted=false) — must still be rejected
        // under acceptAmbiguous=false. Proves the shortcut hasn't broken existing semantics.
        CalendarEventIngestionService.IncomingCalendarEvent incoming =
                new CalendarEventIngestionService.IncomingCalendarEvent(
                        externalEventId,
                        Instant.parse("2026-05-28T10:00:00Z"),
                        Instant.parse("2026-05-28T11:00:00Z"),
                        false, false,
                        null, null, null, "different-payload-hash",
                        "calendar-id", "Updated title", null, null);

        boolean applied = service(false).shouldApplyAndAdvance(connectionId, "MICROSOFT", incoming);

        assertThat(applied).isFalse();
        verify(repository, never()).saveAndFlush(any(ProviderEventProjection.class));
    }
}
