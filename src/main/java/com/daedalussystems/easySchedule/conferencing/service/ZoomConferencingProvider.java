package com.daedalussystems.easySchedule.conferencing.service;

import com.daedalussystems.easySchedule.calendar.auth.TokenCipher;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.conferencing.client.ZoomApiClient;
import com.daedalussystems.easySchedule.conferencing.domain.ConferencingConnectionStatus;
import com.daedalussystems.easySchedule.conferencing.domain.ZoomConferencingConnection;
import com.daedalussystems.easySchedule.conferencing.repository.ZoomConferencingConnectionRepository;
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
        connection.setLastTokenExpiresAt(refreshed.expiresAt());
        connection.setStatus(ConferencingConnectionStatus.ACTIVE);
        repository.save(connection);
        return refreshed.accessToken();
    }
}
