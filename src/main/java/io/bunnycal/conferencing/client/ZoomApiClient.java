package io.bunnycal.conferencing.client;

import java.time.Instant;

public interface ZoomApiClient {
    OAuthTokenExchangeResult exchangeCodeForToken(String code, String redirectUri, String clientId, String clientSecret);

    TokenRefreshResult refreshAccessToken(String refreshToken, String clientId, String clientSecret);

    void revokeToken(String token);

    String fetchProviderUserId(String accessToken);

    MeetingDetails createMeeting(String accessToken, String topic, Instant start, Instant end);

    MeetingDetails updateMeeting(String accessToken, String meetingId, String topic, Instant start, Instant end);

    void deleteMeeting(String accessToken, String meetingId);

    record OAuthTokenExchangeResult(String accessToken, String refreshToken, Instant expiresAt) {}

    record TokenRefreshResult(String accessToken, Instant expiresAt) {}

    record MeetingDetails(String meetingId, String joinUrl, String hostUrl) {}
}
