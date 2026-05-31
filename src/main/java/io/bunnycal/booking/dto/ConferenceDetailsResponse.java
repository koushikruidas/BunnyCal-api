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
}
