package io.bunnycal.conferencing.service;

import io.bunnycal.calendar.auth.TokenCipher;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.client.ZoomApiClient;
import io.bunnycal.conferencing.domain.ConferencingConnectionStatus;
import io.bunnycal.conferencing.domain.ZoomConferencingConnection;
import io.bunnycal.conferencing.repository.ZoomConferencingConnectionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ZoomConferencingProvider implements ConferencingProvider {
    private final ZoomConferencingConnectionRepository repository;
    private final ZoomApiClient zoomApiClient;
    private final TokenCipher tokenCipher;
    private final String clientId;
    private final String clientSecret;

    public ZoomConferencingProvider(ZoomConferencingConnectionRepository repository,
                                    ZoomApiClient zoomApiClient,
                                    TokenCipher tokenCipher,
                                    @Value("${zoom.oauth.client-id:}") String clientId,
                                    @Value("${zoom.oauth.client-secret:}") String clientSecret) {
        this.repository = repository;
        this.zoomApiClient = zoomApiClient;
        this.tokenCipher = tokenCipher;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public ConferencingProviderType providerType() {
        return ConferencingProviderType.ZOOM;
    }

    @Override
    public MeetingDetails createMeeting(UUID bookingId, UUID hostId, String topic, Instant start, Instant end) {
        String token = accessToken(hostId);
        var meeting = zoomApiClient.createMeeting(token, topic, start, end);
        return new MeetingDetails(meeting.meetingId(), meeting.joinUrl(), meeting.hostUrl());
    }

    @Override
    public MeetingDetails updateMeeting(UUID bookingId, UUID hostId, String meetingId, String topic, Instant start, Instant end) {
        String token = accessToken(hostId);
        var meeting = zoomApiClient.updateMeeting(token, meetingId, topic, start, end);
        return new MeetingDetails(meeting.meetingId(), meeting.joinUrl(), meeting.hostUrl());
    }

    @Override
    public void cancelMeeting(UUID bookingId, UUID hostId, String meetingId) {
        String token = accessToken(hostId);
        zoomApiClient.deleteMeeting(token, meetingId);
    }

    private String accessToken(UUID hostId) {
        ZoomConferencingConnection connection = repository.findByUserId(hostId)
                .orElseThrow(() -> new IllegalStateException("zoom conferencing connection not found"));
        if (connection.getStatus() == ConferencingConnectionStatus.REVOKED) {
            throw new IllegalStateException("zoom conferencing revoked");
        }
        String refreshToken = tokenCipher.decrypt(connection.getRefreshTokenCiphertext());
        ZoomApiClient.TokenRefreshResult refreshed = zoomApiClient.refreshAccessToken(refreshToken, clientId, clientSecret);
        if (refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank()
                && !refreshed.refreshToken().equals(refreshToken)) {
            connection.setRefreshTokenCiphertext(tokenCipher.encrypt(refreshed.refreshToken()));
        }
        connection.setLastTokenExpiresAt(refreshed.expiresAt());
        connection.setStatus(ConferencingConnectionStatus.ACTIVE);
        repository.save(connection);
        return refreshed.accessToken();
    }
}
