package io.bunnycal.experience.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "booking_experiences", indexes = {
    @Index(name = "idx_be_owner", columnList = "owner_id"),
    @Index(name = "idx_be_event_type", columnList = "event_type_id")
})
public class BookingExperience extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 120)
    private String slug;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "form_id")
    private UUID formId;

    @Column(name = "primary_color", length = 20)
    private String primaryColor;

    @Builder.Default
    @Column(name = "show_branding", nullable = false)
    private boolean showBranding = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExperienceStatus status = ExperienceStatus.DRAFT;

    @Builder.Default
    @Column(nullable = false)
    private long version = 1L;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
