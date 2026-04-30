package com.daedalussystems.easySchedule.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserTimezoneServiceImplTest {

    private UserTimezoneServiceImpl userTimezoneServiceImpl;

    @BeforeEach
    void setUp() {
        userTimezoneServiceImpl = new UserTimezoneServiceImpl();
    }

    @Test
    void timezoneForCreateDefaultsToUtcWhenNull() {
        assertEquals("UTC", userTimezoneServiceImpl.timezoneForCreate(null));
    }

    @Test
    void timezoneForCreateAcceptsValidTimezone() {
        assertEquals("Asia/Kolkata", userTimezoneServiceImpl.timezoneForCreate("Asia/Kolkata"));
    }

    @Test
    void timezoneForCreateRejectsInvalidTimezone() {
        CustomException ex = assertThrows(CustomException.class,
                () -> userTimezoneServiceImpl.timezoneForCreate("Invalid/Zone"));
        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }

    @Test
    void applyTimezoneUpdateDoesNotOverwriteWhenNull() {
        User user = User.builder().timezone("UTC").build();

        userTimezoneServiceImpl.applyTimezoneUpdate(user, null);

        assertEquals("UTC", user.getTimezone());
    }

    @Test
    void applyTimezoneUpdateDoesNotOverwriteWhenBlank() {
        User user = User.builder().timezone("UTC").build();

        userTimezoneServiceImpl.applyTimezoneUpdate(user, "   ");

        assertEquals("UTC", user.getTimezone());
    }

    @Test
    void applyTimezoneUpdateRejectsInvalidTimezone() {
        User user = User.builder().timezone("UTC").build();

        CustomException ex = assertThrows(CustomException.class,
                () -> userTimezoneServiceImpl.applyTimezoneUpdate(user, "Invalid/Zone"));

        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }
}
