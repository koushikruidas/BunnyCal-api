package com.daedalussystems.easySchedule.availability.domain;

import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "event_types",
        indexes = {
            @Index(name = "idx_event_types_user", columnList = "user_id")
        })
public class EventType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(length = 255)
    private String location;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "conferencing_provider", nullable = false, length = 32)
    private ConferencingProviderType conferencingProvider = ConferencingProviderType.GOOGLE_MEET;

    @Column(name = "custom_conference_url", length = 1024)
    private String customConferenceUrl;

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(nullable = false)
    private Duration duration;

    @Column(name = "buffer_before", nullable = false)
    private Duration bufferBefore;

    @Column(name = "buffer_after", nullable = false)
    private Duration bufferAfter;

    @Column(name = "slot_interval", nullable = false)
    private Duration slotInterval;

    @Column(name = "min_notice", nullable = false)
    private Duration minNotice;

    @Column(name = "max_advance", nullable = false)
    private Duration maxAdvance;

    @Column(name = "hold_duration", nullable = false)
    private Duration holdDuration;
}
