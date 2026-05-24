package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConferencingExecutionPolicy {
    private static final Logger log = LoggerFactory.getLogger(ConferencingExecutionPolicy.class);

    // TEMP MIGRATION ROLLBACK FLAG.
    // Sunset plan: replace this boolean with typed execution outcomes
    // (e.g. ConferencingExecutionResult) and remove legacy passthrough path.
    // Target removal: before completing Phase-1 step-3 rollout.
    private final boolean decoupleNativeProviderMatch;

    public ConferencingExecutionPolicy(
            @Value("${conferencing.orchestration.decouple-native-provider-match:true}")
            boolean decoupleNativeProviderMatch) {
        this.decoupleNativeProviderMatch = decoupleNativeProviderMatch;
    }

    public ConferencingExecutionResult adaptForMirrorProvider(ConferencingInstruction instruction,
                                                              String mirrorProvider,
                                                              UUID bookingId,
                                                              String action) {
        if (instruction == null || !instruction.requestsNativeMeet()) {
            return ConferencingExecutionResult.applied(
                    instruction == null ? ConferencingInstruction.none() : instruction);
        }

        CalendarProviderType mirrorProviderType = parseMirrorProvider(mirrorProvider);
        ConferencingProviderType conferencingProvider = instruction.providerType();

        boolean nativeMatch = (conferencingProvider == ConferencingProviderType.GOOGLE_MEET
                && mirrorProviderType == CalendarProviderType.GOOGLE)
                || (conferencingProvider == ConferencingProviderType.MICROSOFT_TEAMS
                && mirrorProviderType == CalendarProviderType.MICROSOFT);

        if (nativeMatch) {
            return ConferencingExecutionResult.applied(instruction);
        }

        if (!decoupleNativeProviderMatch) {
            log.warn("conferencing_native_provider_mismatch_passthrough bookingId={} action={} mirrorProvider={} conferencingProvider={} policy=legacy_strict_passthrough",
                    bookingId, action, mirrorProvider, conferencingProvider);
            return ConferencingExecutionResult.legacyPassthrough(instruction, "native_provider_mismatch");
        }

        log.info("conferencing_native_provider_mismatch_decoupled bookingId={} action={} mirrorProvider={} conferencingProvider={} fallback=none",
                bookingId, action, mirrorProvider, conferencingProvider);
        return ConferencingExecutionResult.degraded(ConferencingInstruction.none(), "native_provider_mismatch");
    }

    private static CalendarProviderType parseMirrorProvider(String mirrorProvider) {
        if (mirrorProvider == null || mirrorProvider.isBlank()) {
            return null;
        }
        try {
            return CalendarProviderType.valueOf(mirrorProvider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
