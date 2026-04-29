package com.daedalussystems.easySchedule.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserTimezoneServiceTest {

    private UserTimezoneService userTimezoneService;

    @BeforeEach
    void setUp() {
        userTimezoneService = new UserTimezoneService();
    }

    @Test
    void timezoneForCreateDefaultsToUtcWhenNull() {
        assertEquals("UTC", userTimezoneService.timezoneForCreate(null));
    }

    @Test
    void timezoneForCreateAcceptsValidTimezone() {
        assertEquals("Asia/Kolkata", userTimezoneService.timezoneForCreate("Asia/Kolkata"));
    }

    @Test
    void timezoneForCreateRejectsInvalidTimezone() {
        CustomException ex = assertThrows(CustomException.class,
                () -> userTimezoneService.timezoneForCreate("Invalid/Zone"));
        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }

    @Test
    void applyTimezoneUpdateDoesNotOverwriteWhenNull() {
        User user = User.builder().timezone("UTC").build();

        userTimezoneService.applyTimezoneUpdate(user, null);

        assertEquals("UTC", user.getTimezone());
    }

    @Test
    void applyTimezoneUpdateDoesNotOverwriteWhenBlank() {
        User user = User.builder().timezone("UTC").build();

        userTimezoneService.applyTimezoneUpdate(user, "   ");

        assertEquals("UTC", user.getTimezone());
    }

    @Test
    void applyTimezoneUpdateRejectsInvalidTimezone() {
        User user = User.builder().timezone("UTC").build();

        CustomException ex = assertThrows(CustomException.class,
                () -> userTimezoneService.applyTimezoneUpdate(user, "Invalid/Zone"));

        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }
}
