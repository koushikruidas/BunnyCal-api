package com.daedalussystems.easySchedule.sync.reconcile;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
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
