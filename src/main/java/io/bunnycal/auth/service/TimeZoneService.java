package io.bunnycal.auth.service;

import io.bunnycal.auth.domain.user.User;

public interface TimeZoneService {
    String timezoneForCreate(String timezone);
    void applyTimezoneUpdate(User user, String timezone);
}
