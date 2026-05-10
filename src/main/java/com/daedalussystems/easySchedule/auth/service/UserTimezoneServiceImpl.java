package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.common.time.TimezoneService;
import org.springframework.stereotype.Service;

@Service
public class UserTimezoneServiceImpl implements TimeZoneService {

    public static final String DEFAULT_TIMEZONE = "UTC";
    private final TimezoneService timezoneService;

    public UserTimezoneServiceImpl(TimezoneService timezoneService) {
        this.timezoneService = timezoneService;
    }

    public String timezoneForCreate(String timezone) {
        return timezoneService.normalizeOrDefault(timezone, DEFAULT_TIMEZONE);
    }

    public void applyTimezoneUpdate(User user, String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return;
        }
        user.setTimezone(timezoneService.normalizeRequired(timezone));
    }
}
