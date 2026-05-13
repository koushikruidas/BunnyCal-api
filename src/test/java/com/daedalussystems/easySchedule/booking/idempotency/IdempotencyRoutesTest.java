package com.daedalussystems.easySchedule.booking.idempotency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdempotencyRoutesTest {

    @Test
    void canonicalRoutes_fitRouteColumnConstraint() {
        assertTrue(IdempotencyRoutes.API_BOOKINGS_CREATE.length() <= 64);
        assertTrue(IdempotencyRoutes.API_BOOKINGS_CANCEL.length() <= 64);
        assertTrue(IdempotencyRoutes.PUBLIC_BOOK_HOLD.length() <= 64);
        assertTrue(IdempotencyRoutes.PUBLIC_BOOK_CANCEL.length() <= 64);
        assertTrue(IdempotencyRoutes.PUBLIC_BOOK_RESCHEDULE.length() <= 64);
    }
}
