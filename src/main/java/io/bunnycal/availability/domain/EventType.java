package io.bunnycal.availability.domain;

import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.calendar.domain.CalendarProviderType;
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
import java.time.Instant;
import java.util.UUID;
import io.bunnycal.availability.domain.EventKind;
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

    /**
     * Either {@link ConferencingProviderType#DEFAULT} — "use my global default meeting link",
     * resolved against the writer at booking time — or a provider-independent override
     * ({@code ZOOM}, {@code CUSTOM_URL}, {@code NONE}).
     *
     * <p>Never {@code GOOGLE_MEET} or {@code MICROSOFT_TEAMS}: those are coupled to a calendar
     * provider, so freezing one here would break the day the owner moved their write-back calendar
     * to the other provider. They are reachable only through the pointer.
     *
     * <p>Which calendars block this event, and which calendar receives it, are no longer stored
     * here at all — both are properties of the user (see {@code calendar_connection_calendars}).
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "conferencing_provider", nullable = false, length = 32)
    private ConferencingProviderType conferencingProvider = ConferencingProviderType.DEFAULT;

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

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private EventKind kind = EventKind.ONE_ON_ONE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "availability_mode", nullable = false, length = 16)
    private EventAvailabilityMode availabilityMode = EventAvailabilityMode.INHERIT;

    @Builder.Default
    @Column(name = "capacity", nullable = false)
    private int capacity = 1;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "group_host_notification_mode", nullable = false, length = 32)
    private GroupHostNotificationMode groupHostNotificationMode = GroupHostNotificationMode.SMART_SUMMARY;

    @Builder.Default
    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Column(name = "last_degraded_notification_at")
    private Instant lastDegradedNotificationAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
