package io.bunnycal.conferencing.service;

import io.bunnycal.common.enums.ConferencingProviderType;
import java.time.Instant;
import java.util.UUID;

public interface ConferencingProvider {
    ConferencingProviderType providerType();

    MeetingDetails createMeeting(UUID bookingId, UUID hostId, String topic, Instant start, Instant end);

    MeetingDetails updateMeeting(UUID bookingId, UUID hostId, String meetingId, String topic, Instant start, Instant end);

    void cancelMeeting(UUID bookingId, UUID hostId, String meetingId);

    record MeetingDetails(String meetingId, String joinUrl, String hostUrl) {}
}
