package com.daedalussystems.easySchedule.calendar.domain;

import com.daedalussystems.easySchedule.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "provider_event_projections",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_provider_event_projection_key",
                        columnNames = {"connection_id", "provider", "external_event_id"})
        },
        indexes = {
                @Index(name = "idx_provider_event_projection_booking", columnList = "booking_id"),
                @Index(name = "idx_provider_event_projection_observed", columnList = "provider,last_observed_at")
        })
public class ProviderEventProjection extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "external_event_id", nullable = false, length = 255)
    private String externalEventId;

    @Column(name = "projection_status", nullable = false, length = 24)
    private String projectionStatus;

    @Column(name = "projection_version", nullable = false)
    private long projectionVersion;

    @Column(name = "provider_sequence")
    private Long providerSequence;

    @Column(name = "provider_updated_at")
    private Instant providerUpdatedAt;

    @Column(name = "provider_etag", length = 255)
    private String providerEtag;

    @Column(name = "payload_hash", length = 128)
    private String payloadHash;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public String getProjectionStatus() { return projectionStatus; }
    public void setProjectionStatus(String projectionStatus) { this.projectionStatus = projectionStatus; }
    public long getProjectionVersion() { return projectionVersion; }
    public void setProjectionVersion(long projectionVersion) { this.projectionVersion = projectionVersion; }
    public Long getProviderSequence() { return providerSequence; }
    public void setProviderSequence(Long providerSequence) { this.providerSequence = providerSequence; }
    public Instant getProviderUpdatedAt() { return providerUpdatedAt; }
    public void setProviderUpdatedAt(Instant providerUpdatedAt) { this.providerUpdatedAt = providerUpdatedAt; }
    public String getProviderEtag() { return providerEtag; }
    public void setProviderEtag(String providerEtag) { this.providerEtag = providerEtag; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant lastObservedAt) { this.lastObservedAt = lastObservedAt; }
}
