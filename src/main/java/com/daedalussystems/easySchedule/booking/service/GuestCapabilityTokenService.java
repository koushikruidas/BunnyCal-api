package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.booking.domain.BookingActionToken;
import com.daedalussystems.easySchedule.booking.repository.BookingActionTokenRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestCapabilityTokenService {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();

    private final BookingActionTokenRepository repository;

    public GuestCapabilityTokenService(BookingActionTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public String issueToken(UUID bookingId, UUID bookingHostId, BookingActionType actionType, Duration ttl, TokenCreatorType createdBy) {
        if (bookingId == null || bookingHostId == null || actionType == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Invalid token issue parameters.");
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        BookingActionToken token = new BookingActionToken();
        token.setId(UUID.randomUUID());
        token.setBookingId(bookingId);
        token.setBookingHostId(bookingHostId);
        token.setTokenHash(hash(rawToken));
        token.setActionType(actionType);
        token.setExpiresAt(Instant.now().plus(ttl));
        token.setCreatedBy(createdBy == null ? TokenCreatorType.SYSTEM : createdBy);
        repository.save(token);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public boolean allows(UUID bookingId, UUID bookingHostId, String rawToken, BookingActionType requestedAction) {
        if (bookingId == null || bookingHostId == null || rawToken == null || rawToken.isBlank() || requestedAction == null) {
            return false;
        }
        return repository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hash(rawToken), Instant.now())
                .filter(t -> bookingId.equals(t.getBookingId()))
                .filter(t -> bookingHostId.equals(t.getBookingHostId()))
                .filter(t -> t.getActionType().allows(requestedAction))
                .isPresent();
    }

    @Transactional
    public void revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hash(rawToken), Instant.now())
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    repository.save(token);
                });
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
