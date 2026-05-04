package com.daedalussystems.easySchedule.booking.domain;

import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import java.util.UUID;

public final class BookingRules {
    private BookingRules() {
    }

    public static void validateReschedule(UUID id, UUID hostId, Instant startTime, Instant endTime) {
        if (id == null || hostId == null || startTime == null || endTime == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "id, hostId, startTime, endTime are required.");
        }
        if (!startTime.isBefore(endTime)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Booking start must be before end.");
        }
    }
}
