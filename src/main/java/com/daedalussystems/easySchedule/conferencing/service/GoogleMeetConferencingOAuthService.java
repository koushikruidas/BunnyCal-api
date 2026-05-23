package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.calendar.service.CalendarOAuthService;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Google Meet does not have a standalone OAuth flow — Meet links are minted by
 * the Google Calendar event create/update pipeline. From the perspective of the
 * conferencing integrations API, "connecting Google Meet" therefore means
 * "ensure the user has an active Google Calendar OAuth connection". This adapter
 * delegates the connect URL to {@link CalendarOAuthService#buildGoogleConnectUrl}
 * and reports status via the same connection.
 */
@Service
public class GoogleMeetConferencingOAuthService implements ConferencingOAuthService {
    private final CalendarOAuthService calendarOAuthService;

    public GoogleMeetConferencingOAuthService(CalendarOAuthService calendarOAuthService) {
        this.calendarOAuthService = calendarOAuthService;
    }

    @Override
    public ConferencingProviderType providerType() {
        return ConferencingProviderType.GOOGLE_MEET;
    }

    @Override
    public ConferencingProviderCapabilities capabilities() {
        return ConferencingProviderCapabilities.managedBy("google_calendar");
    }

    @Override
    public String buildConnectUrl(UUID userId, String source, String returnTo, String bookingSessionId) {
        return calendarOAuthService.buildGoogleConnectUrl(userId, source, returnTo, bookingSessionId);
    }

    @Override
    public String status(UUID userId) {
        return calendarOAuthService.googleConnectionStatus(userId);
    }

    @Override
    public void disconnect(UUID userId) {
        // No-op by contract: capabilities().standaloneDisconnect() is false, so the
        // controller refuses the call before reaching this method. The implementation
        // exists only to satisfy the interface; revoking Meet without tearing down
        // the parent Google Calendar connection is not a coherent operation.
    }
}
