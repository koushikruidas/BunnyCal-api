package io.bunnycal.booking.idempotency;

public final class IdempotencyRoutes {
    private IdempotencyRoutes() {}

    public static final String API_BOOKINGS_CREATE = "POST:api.bookings.create";
    public static final String API_BOOKINGS_CANCEL = "POST:api.bookings.cancel";
    public static final String API_BOOKINGS_RESCHEDULE = "POST:api.bookings.reschedule";
    public static final String PUBLIC_BOOK_HOLD = "POST:public.book.hold";
    public static final String PUBLIC_BOOK_CANCEL = "POST:public.book.cancel";
    public static final String PUBLIC_BOOK_RESCHEDULE = "POST:public.book.reschedule";
    public static final String PUBLIC_BOOK_PAYMENT = "POST:public.book.payment";
}
