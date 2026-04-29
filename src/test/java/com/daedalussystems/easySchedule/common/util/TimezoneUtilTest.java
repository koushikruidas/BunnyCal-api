package com.daedalussystems.easySchedule.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TimezoneUtilTest {

    @Test
    void validTimezoneAccepted() {
        LocalDateTime local = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

        Instant utc = TimezoneUtil.toUtc(local, "Asia/Kolkata");

        assertEquals(Instant.parse("2026-01-01T04:30:00Z"), utc);
    }

    @Test
    void invalidTimezoneThrowsException() {
        CustomException ex = assertThrows(CustomException.class,
                () -> TimezoneUtil.toUtc(LocalDateTime.now(), "Bad/Timezone"));

        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }

    @Test
    void blankTimezoneThrowsException() {
        CustomException ex = assertThrows(CustomException.class,
                () -> TimezoneUtil.toUserTime(Instant.now(), " "));

        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }
}
