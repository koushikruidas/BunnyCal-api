package io.bunnycal.booking.dto;

public record ConferenceDetailsResponse(
        String provider,
        String joinUrl,
        String dialIn,
        String meetingCode,
        String password,
        String sourceOfTruth) {

    public static ConferenceDetailsResponse none() {
        return new ConferenceDetailsResponse("NONE", null, null, null, null, "unknown");
    }

    /**
     * Conference details from a projection row.
     *
     * <p>{@code provider} must be the conferencing platform -- GOOGLE_MEET, ZOOM -- never the
     * calendar the event was written to. The two are not interchangeable: a Zoom meeting on a
     * Google calendar makes them disagree, and the client uses this value to name where the guest
     * is meeting.
     *
     * <p>A known platform with no link still reports the platform. NONE is reserved for a booking
     * that genuinely has no online meeting, which the client reads as in person -- so returning it
     * for a link that merely went missing turns a gap in our data into a wrong claim.
     */
    public static ConferenceDetailsResponse fromProjection(String provider, String joinUrl, String sourceOfTruth) {
        String normalizedUrl = joinUrl == null || joinUrl.isBlank() ? null : joinUrl;
        String normalizedProvider = provider == null || provider.isBlank()
                ? null
                : provider.toUpperCase(java.util.Locale.ROOT);
        if (normalizedProvider == null && normalizedUrl == null) {
            return none();
        }
        return new ConferenceDetailsResponse(
                normalizedProvider == null ? "UNKNOWN" : normalizedProvider,
                normalizedUrl, null, null, null, sourceOfTruth);
    }
}
