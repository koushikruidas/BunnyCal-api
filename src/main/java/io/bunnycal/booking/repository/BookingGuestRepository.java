package io.bunnycal.booking.repository;

import io.bunnycal.booking.domain.BookingGuest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lives under {@code io.bunnycal.booking} rather than alongside the embed entities on purpose:
 * {@code TestApplication} component-scans this package but not {@code io.bunnycal.embed}, so a
 * repository placed there would not load in the booking integration tests.
 */
public interface BookingGuestRepository extends JpaRepository<BookingGuest, UUID> {

    List<BookingGuest> findByBookingIdAndHostId(UUID bookingId, UUID hostId);

    /**
     * The details step is re-submittable — a booker can go Back and resubmit the same held
     * booking — so guests are replaced wholesale rather than appended, which would otherwise
     * collide with the unique index.
     */
    void deleteByBookingIdAndHostId(UUID bookingId, UUID hostId);
}
