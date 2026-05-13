package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.booking.domain.BookingActionToken;
import com.daedalussystems.easySchedule.booking.repository.BookingActionTokenRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestCapabilityTokenService {
    private static final Logger log = LoggerFactory.getLogger(GuestCapabilityTokenService.class);

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();

    private final BookingActionTokenRepository repository;
    private final MeterRegistry meterRegistry;
    private final Counter validationFailureCounter;
    private final Counter validationSuccessCounter;

    public GuestCapabilityTokenService(BookingActionTokenRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.validationFailureCounter = Counter.builder("guest_token_validation_failure_total").register(meterRegistry);
        this.validationSuccessCounter = Counter.builder("guest_token_validation_success_total").register(meterRegistry);
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
        if (bookingId == null || bookingHostId == null || requestedAction == null) {
            recordValidationFailure("INVALID_INPUT", bookingId, bookingHostId, requestedAction);
            return false;
        }
        if (rawToken == null || rawToken.isBlank()) {
            recordValidationFailure("MISSING_TOKEN", bookingId, bookingHostId, requestedAction);
            return false;
        }
        var maybe = repository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hash(rawToken), Instant.now());
        if (maybe.isEmpty()) {
            recordValidationFailure("NOT_FOUND_OR_EXPIRED_OR_REVOKED", bookingId, bookingHostId, requestedAction);
            return false;
        }
        BookingActionToken token = maybe.get();
        if (!bookingId.equals(token.getBookingId()) || !bookingHostId.equals(token.getBookingHostId())) {
            recordValidationFailure("BOOKING_BINDING_MISMATCH", bookingId, bookingHostId, requestedAction);
            return false;
        }
        if (!token.getActionType().allows(requestedAction)) {
            recordValidationFailure("ACTION_SCOPE_MISMATCH", bookingId, bookingHostId, requestedAction);
            return false;
        }
        validationSuccessCounter.increment();
        return true;
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

    private void recordValidationFailure(String reason, UUID bookingId, UUID bookingHostId, BookingActionType requestedAction) {
        validationFailureCounter.increment();
        log.info("guest_token_validation_failed reason={} bookingId={} hostId={} requestedAction={}",
                reason, bookingId, bookingHostId, requestedAction);
    }
}
