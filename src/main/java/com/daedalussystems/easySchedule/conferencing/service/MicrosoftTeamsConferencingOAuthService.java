package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.calendar.service.MicrosoftCalendarOAuthService;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Microsoft Teams meetings are minted by the Microsoft Calendar event pipeline
 * (Graph {@code isOnlineMeeting=true} with provider {@code teamsForBusiness});
 * Teams does not expose a standalone conferencing OAuth. Connecting Teams
 * therefore means ensuring the user holds an active Microsoft Calendar OAuth
 * connection, so we delegate the connect URL and status to
 * {@link MicrosoftCalendarOAuthService}.
 */
@Service
public class MicrosoftTeamsConferencingOAuthService implements ConferencingOAuthService {
    private final MicrosoftCalendarOAuthService microsoftCalendarOAuthService;

    public MicrosoftTeamsConferencingOAuthService(MicrosoftCalendarOAuthService microsoftCalendarOAuthService) {
        this.microsoftCalendarOAuthService = microsoftCalendarOAuthService;
    }

    @Override
    public ConferencingProviderType providerType() {
        return ConferencingProviderType.MICROSOFT_TEAMS;
    }

    @Override
    public ConferencingProviderCapabilities capabilities() {
        return ConferencingProviderCapabilities.managedBy("microsoft_calendar");
    }

    @Override
    public String buildConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
        return microsoftCalendarOAuthService.buildMicrosoftConnectUrl(userId, source, returnTo, bookingSessionId);
    }

    @Override
    public String status(UUID userId) {
        return microsoftCalendarOAuthService.microsoftConnectionStatus(userId);
    }

    @Override
    public void disconnect(UUID userId) {
        // No-op by contract: capabilities().standaloneDisconnect() is false, so the
        // controller refuses the call before reaching this method. Implementation
        // present only to satisfy the interface.
    }
}
