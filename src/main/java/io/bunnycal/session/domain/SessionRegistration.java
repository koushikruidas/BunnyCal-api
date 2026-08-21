package io.bunnycal.session.domain;

import io.bunnycal.common.audit.BaseEntity;
import io.bunnycal.common.enums.AuthProvider;
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
        name = "session_registrations",
        indexes = {
            @Index(name = "idx_session_registrations_session", columnList = "session_id, host_id"),
            @Index(name = "idx_session_registrations_pending_expiry", columnList = "expires_at")
        })
public class SessionRegistration extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "guest_email", nullable = false, length = 255)
    private String guestEmail;

    @Column(name = "guest_name", length = 120)
    private String guestName;

    @Column(name = "guest_notes", columnDefinition = "TEXT")
    private String guestNotes;

    /**
     * The zone this attendee registered from, so their mail can state the time in it. Null for
     * registrations taken before V149_0 and for any non-browser path; readers fall back to the
     * host's zone, never to UTC.
     */
    @Column(name = "guest_timezone", length = 64)
    private String guestTimezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "invitee_auth_provider", length = 32)
    private AuthProvider inviteeAuthProvider;

    @Column(name = "invitee_provider_user_id", length = 255)
    private String inviteeProviderUserId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RegistrationStatus status = RegistrationStatus.PENDING;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private long version = 0L;
}
