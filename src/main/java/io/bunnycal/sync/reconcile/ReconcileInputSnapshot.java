package io.bunnycal.sync.reconcile;

import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.bunnycal.sync.state.SyncJobStatus;
import java.util.UUID;

public record ReconcileInputSnapshot(
        UUID syncJobId,
        UUID bookingId,
        String provider,
        String externalEventId,
        SyncJobStatus syncJobStatus,
        SyncDesiredAction desiredAction,
        CalendarService.ObserveEventStatus observedStatus,
        String observedErrorCode,
        Long projectionVersion,
        Long terminalIntentEpoch
) {
}
