package com.daedalussystems.easySchedule.auth.repository;

import com.daedalussystems.easySchedule.auth.domain.token.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying
    @Query("delete from RefreshToken rt where rt.id = :id and rt.tokenHash = :tokenHash")
    int deleteByIdAndTokenHash(@Param("id") UUID id, @Param("tokenHash") String tokenHash);

    void deleteByExpiryDateBefore(java.time.Instant now);

    @Transactional
    @Modifying
    @Query("delete from RefreshToken rt where rt.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
