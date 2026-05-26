package io.bunnycal.calendar.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "calendar_provider_operations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_calendar_provider_op_idempotency", columnNames = {"provider", "idempotency_key"})
})
public class CalendarProviderOperation extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CalendarProviderType provider;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CalendarOperationStatus status;

    @Column(name = "external_event_id", length = 255)
    private String externalEventId;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    public UUID getId() { return id; }
    public CalendarProviderType getProvider() { return provider; }
    public void setProvider(CalendarProviderType provider) { this.provider = provider; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public CalendarOperationStatus getStatus() { return status; }
    public void setStatus(CalendarOperationStatus status) { this.status = status; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
}
