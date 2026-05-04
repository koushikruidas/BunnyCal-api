package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import java.util.UUID;

public record SyncJobPlan(
        InternalRefType internalRefType,
        UUID internalRefId,
        SyncDesiredAction desiredAction,
        String provider
) {
}
