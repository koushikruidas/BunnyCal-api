package com.daedalussystems.easySchedule.booking.repository;

import com.daedalussystems.easySchedule.booking.domain.BookingActionToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingActionTokenRepository extends JpaRepository<BookingActionToken, UUID> {
    Optional<BookingActionToken> findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(String tokenHash, Instant now);
}
