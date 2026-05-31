package io.bunnycal.calendar.service;

import io.bunnycal.calendar.repository.CalendarWebhookEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarWebhookDedupService {
    private static final Logger log = LoggerFactory.getLogger(CalendarWebhookDedupService.class);

    private final CalendarWebhookEventRepository repository;
    private final Counter duplicateCounter;
    private final Counter replayRejectedCounter;

    public CalendarWebhookDedupService(CalendarWebhookEventRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.duplicateCounter = Counter.builder("webhook_duplicate_total").register(meterRegistry);
        this.replayRejectedCounter = Counter.builder("webhook_replay_rejected_total").register(meterRegistry);
    }

    @Transactional
    public boolean firstSeen(String provider, UUID connectionId, String providerEventId, String rawPayload) {
        return checkAndRecord(provider, connectionId, providerEventId, rawPayload).firstSeen();
    }

    @Transactional
    public DedupOutcome checkAndRecord(String provider, UUID connectionId, String providerEventId, String rawPayload) {
        if (provider == null || provider.isBlank() || providerEventId == null || providerEventId.isBlank()) {
            return new DedupOutcome(false, null, null);
        }
        String normalizedProvider = provider.trim().toUpperCase();
        String payloadHash = hashNullable(rawPayload);
        String deliveryKey = normalizedProvider + ":" + connectionId + ":" + providerEventId.trim() + ":" + payloadHash;
        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(),
                normalizedProvider,
                providerEventId.trim(),
                connectionId,
                deliveryKey,
                payloadHash,
                Instant.now()
        );
        if (inserted == 0) {
            duplicateCounter.increment();
            replayRejectedCounter.increment();
            log.info("calendar_webhook_replay_rejected provider={} providerEventId={}", provider, providerEventId);
        }
        return new DedupOutcome(inserted > 0, deliveryKey, payloadHash);
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

    public record DedupOutcome(boolean firstSeen, String deliveryKey, String payloadHash) {
    }
}
