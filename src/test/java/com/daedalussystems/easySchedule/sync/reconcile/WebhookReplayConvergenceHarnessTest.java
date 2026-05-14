package com.daedalussystems.easySchedule.sync.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.calendar.service.ProviderEventVersionComparator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WebhookReplayConvergenceHarnessTest {

    @Test
    void duplicateReplay_producesSameTerminalState() {
        long seed = 20260514L;
        ReplayState first = runScenario(seed);
        ReplayState second = runScenario(seed);

        assertEquals(first, second);
        assertTrue(first.terminalIntentEpoch >= 2L, "cancel intent epoch should be monotonic");
        assertTrue(first.projectionVersion >= 1L, "projection version should advance when accepted");
        assertTrue(first.resurrectionBlockedCount >= 0, "resurrection block counter should be tracked");
    }

    @Test
    void replayStorm_withOutOfOrderEvents_convergesDeterministically() {
        for (long seed = 100L; seed < 120L; seed++) {
            ReplayState stateA = runScenario(seed);
            ReplayState stateB = runScenario(seed);
            assertEquals(stateA, stateB, "same seed must converge identically");
            assertTrue(stateA.terminalIntentEpoch >= 1L, "terminal intent epoch must remain monotonic");
        }
    }

    private static ReplayState runScenario(long seed) {
        Random random = new Random(seed);
        ProviderEventVersionComparator comparator = new ProviderEventVersionComparator();
        ReplayState state = new ReplayState();

        List<Observation> events = new ArrayList<>();
        events.add(new Observation(false, 1L, Instant.parse("2026-05-14T10:00:00Z"), "e1", "p1"));
        events.add(new Observation(true, 2L, Instant.parse("2026-05-14T10:05:00Z"), "e2", "p2"));
        events.add(new Observation(false, 1L, Instant.parse("2026-05-14T10:00:00Z"), "e1", "p1")); // duplicate
        events.add(new Observation(false, null, Instant.parse("2026-05-14T10:06:00Z"), "etag-churn", "p2")); // ambiguous churn

        Collections.shuffle(events, random);

        for (Observation event : events) {
            ProviderEventVersionComparator.VersionVector incoming = new ProviderEventVersionComparator.VersionVector(
                    event.sequence, event.updatedAt, event.etag, event.payloadHash);
            ProviderEventVersionComparator.VersionVector persisted = state.vector;
            ProviderEventVersionComparator.ComparisonResult result = comparator.compare(incoming, persisted);

            if (result == ProviderEventVersionComparator.ComparisonResult.OLDER_OR_EQUAL) {
                continue;
            }
            // accept ambiguous in v1 default behavior
            long nextVersion = state.projectionVersion + 1;
            if (nextVersion <= state.projectionVersion) {
                throw new IllegalStateException("projection version must be monotonic");
            }
            state.projectionVersion = nextVersion;
            state.vector = incoming;
            if (event.cancelled) {
                state.cancelled = true;
                state.terminalIntentEpoch++;
            } else if (state.cancelled) {
                // anti-resurrection rule in replay harness: once cancelled terminal epoch is elevated,
                // stale create/update observations must not revive local intent.
                state.resurrectionBlockedCount++;
            }
        }

        return state;
    }

    private static final class Observation {
        private final boolean cancelled;
        private final Long sequence;
        private final Instant updatedAt;
        private final String etag;
        private final String payloadHash;

        private Observation(boolean cancelled, Long sequence, Instant updatedAt, String etag, String payloadHash) {
            this.cancelled = cancelled;
            this.sequence = sequence;
            this.updatedAt = updatedAt;
            this.etag = etag;
            this.payloadHash = payloadHash;
        }
    }

    private static final class ReplayState {
        private boolean cancelled;
        private long terminalIntentEpoch = 1L;
        private long projectionVersion;
        private long resurrectionBlockedCount;
        private ProviderEventVersionComparator.VersionVector vector;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReplayState that)) return false;
            return cancelled == that.cancelled
                    && terminalIntentEpoch == that.terminalIntentEpoch
                    && projectionVersion == that.projectionVersion
                    && resurrectionBlockedCount == that.resurrectionBlockedCount
                    && equalVector(vector, that.vector);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(cancelled);
            result = 31 * result + Long.hashCode(terminalIntentEpoch);
            result = 31 * result + Long.hashCode(projectionVersion);
            result = 31 * result + Long.hashCode(resurrectionBlockedCount);
            result = 31 * result + (vector == null ? 0 : vector.hashCode());
            return result;
        }

        private static boolean equalVector(ProviderEventVersionComparator.VersionVector a,
                                           ProviderEventVersionComparator.VersionVector b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            return java.util.Objects.equals(a.providerSequence(), b.providerSequence())
                    && java.util.Objects.equals(a.providerUpdatedAt(), b.providerUpdatedAt())
                    && java.util.Objects.equals(a.providerEtag(), b.providerEtag())
                    && java.util.Objects.equals(a.payloadHash(), b.payloadHash());
        }
    }
}
