package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;

public interface TimeZoneService {
    String timezoneForCreate(String timezone);
    void applyTimezoneUpdate(User user, String timezone);
}
