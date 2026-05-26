package io.bunnycal.calendar.dto;

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
            Roles roles
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
}

