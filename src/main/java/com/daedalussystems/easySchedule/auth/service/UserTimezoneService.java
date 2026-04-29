package com.daedalussystems.easySchedule.auth.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.common.util.TimezoneUtil;
import org.springframework.stereotype.Service;

@Service
public class UserTimezoneService {

    public static final String DEFAULT_TIMEZONE = "UTC";

    public String timezoneForCreate(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return DEFAULT_TIMEZONE;
        }
        String normalized = timezone.trim();
        TimezoneUtil.validate(normalized);
        return normalized;
    }

    public void applyTimezoneUpdate(User user, String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return;
        }
        String normalized = timezone.trim();
        TimezoneUtil.validate(normalized);
        user.setTimezone(normalized);
    }
}
