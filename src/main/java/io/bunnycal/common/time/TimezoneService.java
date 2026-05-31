package io.bunnycal.common.time;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

@Service
public class TimezoneService {

    public ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            throw new CustomException(ErrorCode.INVALID_TIMEZONE);
        }
    }

    public String normalizeOrDefault(String timezone, String defaultTimezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return defaultTimezone;
        }
        resolveZone(timezone);
        return timezone.trim();
    }

    public String normalizeRequired(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_TIMEZONE);
        }
        resolveZone(timezone);
        return timezone.trim();
    }
}

