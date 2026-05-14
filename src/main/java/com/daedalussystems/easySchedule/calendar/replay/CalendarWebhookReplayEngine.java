package com.daedalussystems.easySchedule.calendar.replay;

import com.daedalussystems.easySchedule.calendar.service.ProviderEventVersionComparator;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;

@Service
public class CalendarWebhookReplayEngine {

    private final ProviderEventVersionComparator comparator;
    private final MeterRegistry meterRegistry;

    public CalendarWebhookReplayEngine(ProviderEventVersionComparator comparator, MeterRegistry meterRegistry) {
        this.comparator = comparator;
        this.meterRegistry = meterRegistry;
    }

    public WebhookReplayReport replay(List<WebhookReplayFixture> sourceFixtures, WebhookReplayOptions options) {
        List<WebhookReplayFixture> fixtures = materializeSequence(sourceFixtures, options);
        ReplayState state = new ReplayState();

        for (WebhookReplayFixture fixture : fixtures) {
            state.processedCount++;
            ProviderEventVersionComparator.VersionVector incoming = new ProviderEventVersionComparator.VersionVector(
                    fixture.providerSequence(), fixture.providerUpdatedAt(), fixture.providerEtag(), fixture.payloadHash());

            if ("DUPLICATE".equalsIgnoreCase(fixture.dedupResult())) {
                state.duplicateCollapsedCount++;
                state.projectionNoopCount++;
                meterRegistry.counter("sync.replay.duplicate_collapsed.total", "reason", "captured_duplicate").increment();
                meterRegistry.counter("sync.replay.projection.noop.total", "reason", "captured_duplicate").increment();
                continue;
            }

            ProviderEventVersionComparator.ComparisonResult result = comparator.compare(incoming, state.vector);

            if (result == ProviderEventVersionComparator.ComparisonResult.OLDER_OR_EQUAL) {
                state.staleRejectedCount++;
                state.projectionNoopCount++;
                meterRegistry.counter("sync.replay.rejected.total", "reason", "older_or_equal").increment();
                meterRegistry.counter("sync.replay.projection.noop.total", "reason", "older_or_equal").increment();
                continue;
            }
            if (result == ProviderEventVersionComparator.ComparisonResult.AMBIGUOUS_NEWER_HINT) {
                state.ambiguousCount++;
                meterRegistry.counter("sync.replay.ambiguous.total", "decision", "accepted").increment();
            }

            boolean cancelled = inferCancelled(fixture.rawPayload());
            if ("CANCELLED".equals(state.terminalStatus) && !cancelled) {
                state.resurrectionBlockedCount++;
                state.projectionNoopCount++;
                meterRegistry.counter("sync.replay.resurrection_blocked.total", "reason", "cancelled_terminal_guard").increment();
                meterRegistry.counter("sync.replay.projection.noop.total", "reason", "cancelled_terminal_guard").increment();
                continue;
            }

            state.vector = incoming;
            state.projectionVersion++;
            state.projectionAdvancedCount++;
            state.acceptedCount++;
            state.terminalStatus = cancelled ? "CANCELLED" : "ACTIVE";
            if (cancelled) {
                state.terminalIntentEpoch++;
            }
            meterRegistry.counter("sync.replay.projection.advanced.total", "reason", "accepted_observation").increment();
            if (fixture.recurringHint() && result != ProviderEventVersionComparator.ComparisonResult.NEWER) {
                state.recurringDivergenceCount++;
                meterRegistry.counter("sync.replay.recurring_divergence.total", "reason", "ambiguous_or_disorder").increment();
            }

            if (state.terminalIntentEpoch < state.maxObservedTerminalIntentEpoch) {
                state.invariantViolationCount++;
                meterRegistry.counter("sync.replay.invariant_violation.total", "kind", "terminal_intent_non_monotonic").increment();
            }
            state.maxObservedTerminalIntentEpoch = Math.max(state.maxObservedTerminalIntentEpoch, state.terminalIntentEpoch);

            if (state.projectionVersion < state.maxObservedProjectionVersion) {
                state.invariantViolationCount++;
                meterRegistry.counter("sync.replay.invariant_violation.total", "kind", "projection_version_non_monotonic").increment();
            }
            state.maxObservedProjectionVersion = Math.max(state.maxObservedProjectionVersion, state.projectionVersion);
        }

        if (state.invariantViolationCount == 0L) {
            meterRegistry.counter("sync.replay.convergence.total", "result", "success").increment();
        } else {
            meterRegistry.counter("sync.replay.convergence.total", "result", "failure").increment();
        }

        String digest = digest(state.acceptedCount + "|"
                + state.processedCount + "|"
                + state.duplicateCollapsedCount + "|"
                + state.projectionNoopCount + "|"
                + state.projectionAdvancedCount + "|"
                + state.staleRejectedCount + "|"
                + state.ambiguousCount + "|"
                + state.resurrectionBlockedCount + "|"
                + state.recurringDivergenceCount + "|"
                + state.invariantViolationCount + "|"
                + state.projectionVersion + "|"
                + state.terminalIntentEpoch + "|"
                + state.terminalStatus);

        return new WebhookReplayReport(
                state.processedCount,
                state.acceptedCount,
                state.duplicateCollapsedCount,
                state.projectionNoopCount,
                state.projectionAdvancedCount,
                state.staleRejectedCount,
                state.ambiguousCount,
                state.resurrectionBlockedCount,
                state.recurringDivergenceCount,
                state.invariantViolationCount,
                state.projectionVersion,
                state.terminalIntentEpoch,
                state.terminalStatus,
                digest);
    }

    public boolean proveDeterminism(List<WebhookReplayFixture> fixtures, WebhookReplayOptions options) {
        WebhookReplayReport first = replay(fixtures, options);
        WebhookReplayReport second = replay(fixtures, options);
        boolean deterministic = first.equals(second);
        meterRegistry.counter("sync.replay.determinism.total", "result", deterministic ? "stable" : "violation").increment();
        return deterministic;
    }

    private static List<WebhookReplayFixture> materializeSequence(List<WebhookReplayFixture> sourceFixtures, WebhookReplayOptions options) {
        List<WebhookReplayFixture> working = new ArrayList<>();
        int duplicates = Math.max(1, options.duplicateMultiplier());
        for (WebhookReplayFixture fixture : sourceFixtures) {
            for (int i = 0; i < duplicates; i++) {
                working.add(fixture);
            }
        }

        if (options.delayedDeliveryEvery() > 1) {
            List<WebhookReplayFixture> reordered = new ArrayList<>();
            for (int i = 0; i < working.size(); i++) {
                if ((i + 1) % options.delayedDeliveryEvery() != 0) {
                    reordered.add(working.get(i));
                }
            }
            for (int i = 0; i < working.size(); i++) {
                if ((i + 1) % options.delayedDeliveryEvery() == 0) {
                    reordered.add(working.get(i));
                }
            }
            working = reordered;
        }

        if (options.reorder()) {
            Random random = new Random(options.seed());
            for (int i = working.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                WebhookReplayFixture temp = working.get(i);
                working.set(i, working.get(j));
                working.set(j, temp);
            }
        } else {
            working = working.stream()
                    .sorted(Comparator.comparingLong(WebhookReplayFixture::arrivalIndex))
                    .toList();
        }

        if (options.concurrentLanes() > 1 && working.size() > 1) {
            // Deterministic interleaving simulation: rotate each lane window.
            int lanes = Math.max(1, options.concurrentLanes());
            List<WebhookReplayFixture> interleaved = new ArrayList<>();
            for (int lane = 0; lane < lanes; lane++) {
                for (int i = lane; i < working.size(); i += lanes) {
                    interleaved.add(working.get(i));
                }
            }
            working = interleaved;
        }

        return working;
    }

    private static boolean inferCancelled(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return false;
        }
        return rawPayload.contains("\"status\":\"cancelled\"")
                || rawPayload.contains("\"cancelled\":true")
                || rawPayload.contains("\"eventType\":\"delete\"");
    }

    private static String digest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final class ReplayState {
        private long processedCount;
        private long acceptedCount;
        private long duplicateCollapsedCount;
        private long projectionNoopCount;
        private long projectionAdvancedCount;
        private long staleRejectedCount;
        private long ambiguousCount;
        private long resurrectionBlockedCount;
        private long recurringDivergenceCount;
        private long invariantViolationCount;
        private long projectionVersion;
        private long maxObservedProjectionVersion;
        private long terminalIntentEpoch;
        private long maxObservedTerminalIntentEpoch;
        private String terminalStatus = "ACTIVE";
        private ProviderEventVersionComparator.VersionVector vector;
    }
}
