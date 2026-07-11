package io.bunnycal.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimezoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserTimezoneServiceImplTest {

    private UserTimezoneServiceImpl userTimezoneServiceImpl;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        userTimezoneServiceImpl = new UserTimezoneServiceImpl(new TimezoneService(), userRepository);
    }

    /** A new account starts on UTC with timezoneAuto set, so the browser's zone is adopted. */
    @Test
    void adoptDetectedTimezone_adoptsBrowserZone_whenStillAuto() {
        User user = User.builder().timezone("UTC").timezoneAuto(true).build();

        User result = userTimezoneServiceImpl.adoptDetectedTimezone(user, "Asia/Kolkata");

        assertEquals("Asia/Kolkata", result.getTimezone());
        assertTrue(result.isTimezoneAuto(), "still inferred — the host has not chosen a zone");
        verify(userRepository).save(user);
    }

    /** Once the host has chosen a zone, travelling must not silently rewrite it. */
    @Test
    void adoptDetectedTimezone_leavesHostChosenZoneAlone() {
        User user = User.builder().timezone("Europe/London").timezoneAuto(false).build();

        User result = userTimezoneServiceImpl.adoptDetectedTimezone(user, "Asia/Kolkata");

        assertEquals("Europe/London", result.getTimezone());
        verify(userRepository, never()).save(any());
    }

    /** The header is client-supplied and reaches GET /api/me — junk must not fail the request. */
    @Test
    void adoptDetectedTimezone_ignoresInvalidHeader() {
        User user = User.builder().timezone("UTC").timezoneAuto(true).build();

        User result = userTimezoneServiceImpl.adoptDetectedTimezone(user, "Not/AZone");

        assertEquals("UTC", result.getTimezone());
        verify(userRepository, never()).save(any());
    }

    @Test
    void adoptDetectedTimezone_ignoresMissingHeader() {
        User user = User.builder().timezone("UTC").timezoneAuto(true).build();

        assertEquals("UTC", userTimezoneServiceImpl.adoptDetectedTimezone(user, null).getTimezone());
        verify(userRepository, never()).save(any());
    }

    /** No write when the detected zone already matches. */
    @Test
    void adoptDetectedTimezone_noWriteWhenUnchanged() {
        User user = User.builder().timezone("Asia/Kolkata").timezoneAuto(true).build();

        userTimezoneServiceImpl.adoptDetectedTimezone(user, "Asia/Kolkata");

        verify(userRepository, never()).save(any());
    }

    /** Choosing a zone in Settings must stop us inferring one ever again. */
    @Test
    void applyTimezoneUpdate_clearsAutoFlag() {
        User user = User.builder().timezone("UTC").timezoneAuto(true).build();

        userTimezoneServiceImpl.applyTimezoneUpdate(user, "America/New_York");

        assertEquals("America/New_York", user.getTimezone());
        assertFalse(user.isTimezoneAuto(), "an explicit choice must never be auto-adopted over");
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
