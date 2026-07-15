package io.bunnycal.conferencing.service;

import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Enforces that native-meet conferencing (Google Meet, Teams) is only dispatched
 * when the mirror provider can actually provision it. Mismatches are an explicit
 * error — never silently dropped to NONE.
 */
@Component
public class ConferencingExecutionPolicy {
    private static final Logger log = LoggerFactory.getLogger(ConferencingExecutionPolicy.class);

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final NativeConferencingCapabilityService capabilityService;

    public ConferencingExecutionPolicy(CalendarConnectionRepository calendarConnectionRepository,
                                       NativeConferencingCapabilityService capabilityService) {
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.capabilityService = capabilityService;
    }

    public ConferencingExecutionResult adaptForMirrorProvider(ConferencingInstruction instruction,
                                                              String mirrorProvider,
                                                              UUID bookingId,
                                                              String action) {
        return adaptForMirrorProvider(instruction, mirrorProvider, bookingId, action, null);
    }

    public ConferencingExecutionResult adaptForMirrorProvider(ConferencingInstruction instruction,
                                                              String mirrorProvider,
                                                              UUID bookingId,
                                                              String action,
                                                              @Nullable UUID schedulingConnectionId) {
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
            // Teams is advertised by the target calendar itself. Account shape alone is not enough:
            // an Entra user can still lack the Teams licence/provider on its Outlook calendar.
            if (conferencingProvider == ConferencingProviderType.MICROSOFT_TEAMS
                    && schedulingConnectionId != null) {
                CalendarConnection connection = calendarConnectionRepository.findById(schedulingConnectionId).orElse(null);
                if (!capabilityService.canServe(connection, ConferencingProviderType.MICROSOFT_TEAMS)) {
                    log.warn("conferencing_ms_teams_calendar_capability_rejected bookingId={} action={} connectionId={}",
                            bookingId, action, schedulingConnectionId);
                    throw new CustomException(ErrorCode.VALIDATION_ERROR,
                            "Microsoft Teams conferencing is not enabled for the chosen Microsoft calendar. "
                                    + "Choose a work or school calendar with Teams enabled, or use Zoom.");
                }
            }
            log.info("conferencing_native_match bookingId={} action={} mirrorProvider={} conferencingProvider={}",
                    bookingId, action, mirrorProvider, conferencingProvider);
            return ConferencingExecutionResult.applied(instruction);
        }

        // Provider mismatch: native meet cannot be provisioned. Fail explicitly.
        String required = conferencingProvider == ConferencingProviderType.GOOGLE_MEET ? "google" : "microsoft";
        log.warn("conferencing_native_provider_mismatch_rejected bookingId={} action={} mirrorProvider={} conferencingProvider={} requiredProvider={}",
                bookingId, action, mirrorProvider, conferencingProvider, required);
        throw new CustomException(ErrorCode.VALIDATION_ERROR,
                "Conferencing provider " + conferencingProvider.externalId()
                        + " requires a " + required + " calendar connection for provisioning.");
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
