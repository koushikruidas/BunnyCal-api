package com.daedalussystems.easySchedule.sync.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.calendar.service.CalendarService;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconcileSnapshotCanonicalizerTest {

    private final ReconcileSnapshotCanonicalizer canonicalizer =
            new ReconcileSnapshotCanonicalizer();

    @Test
    void hash_isStableForSameSnapshot() {
        ReconcileInputSnapshot snapshot = snapshot("ext-a", "RATE_LIMIT");
        String a = canonicalizer.hash(snapshot);
        String b = canonicalizer.hash(snapshot);

        assertEquals(a, b);
        assertTrue(canonicalizer.canonicalJson(snapshot).contains("\"schemaVersion\":1"));
    }

    @Test
    void hash_changesWhenFieldChanges() {
        String a = canonicalizer.hash(snapshot("ext-a", "RATE_LIMIT"));
        String b = canonicalizer.hash(snapshot("ext-b", "RATE_LIMIT"));

        assertNotEquals(a, b);
    }

    @Test
    void canonicalJson_normalizesUnicodeToNfc() {
        ReconcileInputSnapshot composed = snapshot("e\u0301", "RATE_LIMIT");
        ReconcileInputSnapshot precomposed = snapshot("\u00E9", "RATE_LIMIT");

        assertEquals(canonicalizer.canonicalJson(composed), canonicalizer.canonicalJson(precomposed));
        assertEquals(canonicalizer.hash(composed), canonicalizer.hash(precomposed));
    }

    private static ReconcileInputSnapshot snapshot(String externalEventId, String errorCode) {
        return new ReconcileInputSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "google",
                externalEventId,
                SyncJobStatus.SYNCED,
                SyncDesiredAction.UPDATE,
                CalendarService.ObserveEventStatus.RETRYABLE_FAILURE,
                errorCode,
                10L,
                22L);
    }
}
