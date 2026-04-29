package com.daedalussystems.easySchedule.common.util;

import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class TimezoneUtil {

    private TimezoneUtil() {
    }

    /**
     * Validates an IANA timezone string.
     * Defaulting/null-handling belongs to the service layer.
     */
    public static void validate(String timezone) {
        parseZoneId(timezone);
    }

    public static Instant toUtc(LocalDateTime localDateTime, String timezone) {
        ZoneId zoneId = parseZoneId(timezone);
        return localDateTime.atZone(zoneId).toInstant();
    }

    public static ZonedDateTime toUserTime(Instant utcTime, String timezone) {
        ZoneId zoneId = parseZoneId(timezone);
        return utcTime.atZone(zoneId);
    }

    private static ZoneId parseZoneId(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            throw new CustomException(ErrorCode.INVALID_TIMEZONE);
        }
    }
}
