package com.daedalussystems.easySchedule.availability.booking.repository;

import com.daedalussystems.easySchedule.availability.booking.domain.Booking;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID userId,
            Instant requestedEnd,
            Instant requestedStart);
}
