package io.bunnycal.payments.webhook;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted raw provider webhook event. The {@code (provider, providerEventId)} pair is
 * UNIQUE — the idempotency anchor that guarantees each provider event is processed once.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "webhook_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_events_provider_event",
                columnNames = {"provider", "provider_event_id"}),
        indexes = {
            @Index(name = "idx_webhook_events_status", columnList = "status,received_at"),
            @Index(name = "idx_webhook_events_type", columnList = "type")
        })
public class WebhookEvent extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String provider = "STRIPE";

    @Column(name = "provider_event_id", nullable = false, length = 255)
    private String providerEventId;

    @Column(nullable = false, length = 128)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private WebhookEventStatus status = WebhookEventStatus.RECEIVED;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "received_at", nullable = false)
    @Builder.Default
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;
}
