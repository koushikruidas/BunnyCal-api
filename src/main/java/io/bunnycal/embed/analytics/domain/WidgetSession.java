package io.bunnycal.embed.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "widget_sessions", indexes = {
    @Index(name = "idx_ws_experience", columnList = "booking_experience_id"),
    @Index(name = "idx_ws_anon", columnList = "anonymous_id"),
    @Index(name = "idx_ws_stage", columnList = "current_stage")
})
public class WidgetSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_experience_id", nullable = false)
    private UUID bookingExperienceId;

    @Column(name = "anonymous_id", nullable = false)
    private UUID anonymousId;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "utm_source", length = 255)
    private String utmSource;

    @Column(name = "utm_medium", length = 255)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 255)
    private String utmCampaign;

    @Column(name = "referrer", length = 1024)
    private String referrer;

    @Builder.Default
    @Column(name = "current_stage", nullable = false, length = 32)
    private String currentStage = "WIDGET_LOADED";

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "booking_host_id")
    private UUID bookingHostId;
}
