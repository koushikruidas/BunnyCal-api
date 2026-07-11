package io.bunnycal.auth.service;

import io.bunnycal.auth.domain.user.User;

public interface TimeZoneService {
    String timezoneForCreate(String timezone);

    /** Applies a timezone the host chose themselves, so it is never auto-adopted over again. */
    void applyTimezoneUpdate(User user, String timezone);

    /**
     * Adopts the browser-detected timezone while the host's own is still one we inferred, and
     * returns the (possibly updated) user. No-op once the host has chosen a zone in Settings, or
     * when the header is absent or not a real zone.
     */
    User adoptDetectedTimezone(User user, String detectedTimezone);
}
