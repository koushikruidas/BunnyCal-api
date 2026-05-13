package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.repository.CalendarWebhookEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarWebhookDedupService {

    private final CalendarWebhookEventRepository repository;

    public CalendarWebhookDedupService(CalendarWebhookEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean firstSeen(String provider, String providerEventId, String rawPayload) {
        if (provider == null || provider.isBlank() || providerEventId == null || providerEventId.isBlank()) {
            return false;
        }
        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(),
                provider.trim().toUpperCase(),
                providerEventId.trim(),
                hashNullable(rawPayload),
                Instant.now()
        );
        return inserted > 0;
    }

    private static String hashNullable(String payload) {
        if (payload == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
