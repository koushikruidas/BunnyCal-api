package io.bunnycal.auth.domain.user;

import io.bunnycal.auth.onboarding.OnboardingStatus;
import io.bunnycal.auth.onboarding.OnboardingStep;
import io.bunnycal.auth.onboarding.OnboardingUseCase;
import io.bunnycal.common.audit.BaseEntity;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.UserStatus;
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
@Table(
        name = "users",
        indexes = {
            @Index(name = "idx_users_email", columnList = "email")
        })
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(unique = true, length = 120)
    private String username;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "profile_image_url", length = 1024)
    private String profileImageUrl;

    @Column(name = "avatar_version")
    private Long avatarVersion;

    @Column(nullable = false, length = 50)
    private String timezone;

    /**
     * True while {@link #timezone} is one we inferred rather than one the host chose. Signup
     * happens over an OAuth redirect and cannot see the browser's zone, so new accounts start on
     * UTC with this set; the first authenticated request carrying an X-Timezone header adopts the
     * real zone. Saving a timezone in Settings clears this, so a deliberate choice is never
     * overwritten — not when the host travels, and not when they sign in from another machine.
     */
    @Builder.Default
    @Column(name = "timezone_auto", nullable = false)
    private boolean timezoneAuto = false;

    /** Versioned first-run activation state. Existing accounts are backfilled as completed. */
    @Builder.Default
    @Column(name = "onboarding_version", nullable = false)
    private int onboardingVersion = 1;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, length = 24)
    private OnboardingStatus onboardingStatus = OnboardingStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_use_case", length = 32)
    private OnboardingUseCase onboardingUseCase;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_last_step", length = 32)
    private OnboardingStep onboardingLastStep;

    @Column(name = "availability_confirmed_at")
    private Instant availabilityConfirmedAt;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    /**
     * The user's global default meeting link. Event types storing
     * {@link ConferencingProviderType#DEFAULT} resolve to this at booking time, so changing it
     * carries every one of them forward instead of leaving them pinned to a provider their
     * write-back calendar can no longer serve.
     *
     * <p>Constrained by the write-back calendar's provider (Google → Meet/Zoom/none; Microsoft
     * work-school → Teams/Zoom/none; Microsoft consumer → Zoom/none) and enforced at the settings
     * page, where the user can see and fix it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_conferencing_provider", nullable = false, length = 32)
    @Builder.Default
    private ConferencingProviderType defaultConferencingProvider = ConferencingProviderType.NONE;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;
}
