package io.bunnycal.auth.domain.user;

import io.bunnycal.common.audit.BaseEntity;
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

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;
}
