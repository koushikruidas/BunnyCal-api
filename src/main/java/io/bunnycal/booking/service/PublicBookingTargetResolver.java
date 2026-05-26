package io.bunnycal.booking.service;

import java.util.UUID;

public interface PublicBookingTargetResolver {
    ResolvedTarget resolve(String username, String eventTypeSlug);

    record ResolvedTarget(UUID userId,
                          UUID eventTypeId,
                          String hostName,
                          String hostUsername,
                          String timezone,
                          String hostEmail,
                          String hostAvatarUrl,
                          String eventName,
                          String eventDescription,
                          String eventLocation,
                          java.time.Duration duration,
                          java.time.Duration holdDuration) {
    }
}
