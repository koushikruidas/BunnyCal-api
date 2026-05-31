package io.bunnycal.calendar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record CalendarRuntimeStatusResponse(
        String lifecycleAuthority,
        Identity identity,
        List<ConnectionStatus> connections,
        Conferencing conferencing
) {
    public record Identity(String provider, String email) {}

    public record ConnectionStatus(
            String connectionId,
            String provider,
            String displayName,
            String email,
            String status,
            boolean actionRequired,
            Capabilities capabilities,
            Roles roles,
            @JsonInclude(JsonInclude.Include.NON_NULL) Account account,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Calendar> calendars
    ) {}

    public record Account(
            String type,
            boolean supportsNativeTeams
    ) {}

    public record Capabilities(
            boolean availability,
            boolean projection,
            boolean conferencingProvisioning,
            boolean webhooks
    ) {}

    public record Roles(
            boolean availabilityEligible,
            boolean projectionEligible,
            boolean conferencingEligible
    ) {}

    public record Conferencing(
            boolean zoomConnected,
            boolean googleMeetAvailable,
            boolean teamsAvailable
    ) {}

    public record Calendar(
            String calendarId,
            String name,
            boolean isPrimary,
            boolean canRead,
            boolean canWrite,
            boolean selectedForAvailability,
            boolean selectedForProjection
    ) {}
}
