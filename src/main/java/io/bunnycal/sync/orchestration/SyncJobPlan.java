package io.bunnycal.sync.orchestration;

import io.bunnycal.sync.state.InternalRefType;
import io.bunnycal.sync.state.SyncDesiredAction;
import java.util.UUID;

public record SyncJobPlan(
        InternalRefType internalRefType,
        UUID internalRefId,
        SyncDesiredAction desiredAction,
        String provider
) {
}
