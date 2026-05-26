package io.bunnycal.booking.repository;

import io.bunnycal.booking.domain.BookingActionToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingActionTokenRepository extends JpaRepository<BookingActionToken, UUID> {
    Optional<BookingActionToken> findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(String tokenHash, Instant now);
}
