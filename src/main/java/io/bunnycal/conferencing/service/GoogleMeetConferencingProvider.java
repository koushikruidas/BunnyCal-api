package io.bunnycal.conferencing.service;

import io.bunnycal.common.enums.ConferencingProviderType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class GoogleMeetConferencingProvider implements ConferencingProvider {
    @Override
    public ConferencingProviderType providerType() {
        return ConferencingProviderType.GOOGLE_MEET;
    }

    @Override
    public MeetingDetails createMeeting(UUID bookingId, UUID hostId, String topic, Instant start, Instant end) {
        // Google Meet link is created by the calendar provider event create/update pipeline.
        return new MeetingDetails(null, null, null);
    }

    @Override
    public MeetingDetails updateMeeting(UUID bookingId, UUID hostId, String meetingId, String topic, Instant start, Instant end) {
        return new MeetingDetails(meetingId, null, null);
    }

    @Override
    public void cancelMeeting(UUID bookingId, UUID hostId, String meetingId) {
        // No-op for Google Meet; lifecycle follows calendar event lifecycle.
    }
}
