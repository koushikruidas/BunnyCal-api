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
            /** True on the connection round-robin bookings are written back to. At most one per user. */
            boolean defaultWriteback,
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

    /**
     * @param defaultProvider the user's global default meeting link — what an event type that
     *        follows the default will actually resolve to. Also what the create-event wizard labels
     *        its "your default" option with.
     * @param googleMeetAvailable whether Meet can be chosen as the default: true only when bookings
     *        currently go to a Google calendar, since that is the only place a Meet link can be made.
     * @param teamsAvailable likewise for Teams — a Microsoft calendar on a work/school account.
     */
    public record Conferencing(
            boolean zoomConnected,
            boolean googleMeetAvailable,
            boolean teamsAvailable,
            String defaultProvider
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
