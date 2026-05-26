package io.bunnycal.calendar.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "calendar_webhook_replay_fixtures")
public class CalendarWebhookReplayFixture extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "arrival_index", insertable = false, updatable = false)
    private Long arrivalIndex;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "provider_event_id", nullable = false, length = 255)
    private String providerEventId;

    @Column(name = "delivery_key", nullable = false, length = 255)
    private String deliveryKey;

    @Column(name = "payload_hash", length = 128)
    private String payloadHash;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "dedup_result", nullable = false, length = 16)
    private String dedupResult;

    @Column(name = "provider_updated_at")
    private Instant providerUpdatedAt;

    @Column(name = "provider_etag", length = 255)
    private String providerEtag;

    @Column(name = "provider_sequence")
    private Long providerSequence;

    @Column(name = "delivery_id", length = 255)
    private String deliveryId;

    @Column(name = "source_attribution", nullable = false, length = 32)
    private String sourceAttribution;

    @Column(name = "recurring_hint", nullable = false)
    private boolean recurringHint;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "causation_id", length = 128)
    private String causationId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public UUID getId() { return id; }
    public Long getArrivalIndex() { return arrivalIndex; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }
    public String getDeliveryKey() { return deliveryKey; }
    public void setDeliveryKey(String deliveryKey) { this.deliveryKey = deliveryKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public String getDedupResult() { return dedupResult; }
    public void setDedupResult(String dedupResult) { this.dedupResult = dedupResult; }
    public Instant getProviderUpdatedAt() { return providerUpdatedAt; }
    public void setProviderUpdatedAt(Instant providerUpdatedAt) { this.providerUpdatedAt = providerUpdatedAt; }
    public String getProviderEtag() { return providerEtag; }
    public void setProviderEtag(String providerEtag) { this.providerEtag = providerEtag; }
    public Long getProviderSequence() { return providerSequence; }
    public void setProviderSequence(Long providerSequence) { this.providerSequence = providerSequence; }
    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }
    public String getSourceAttribution() { return sourceAttribution; }
    public void setSourceAttribution(String sourceAttribution) { this.sourceAttribution = sourceAttribution; }
    public boolean isRecurringHint() { return recurringHint; }
    public void setRecurringHint(boolean recurringHint) { this.recurringHint = recurringHint; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
