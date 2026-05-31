package io.bunnycal.conferencing.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "zoom_conferencing_connections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_zoom_conferencing_connections_user", columnNames = {"user_id"})
})
public class ZoomConferencingConnection extends BaseEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "refresh_token_ciphertext", nullable = false, length = 4096)
    private String refreshTokenCiphertext;

    @Column(name = "last_token_expires_at", nullable = false)
    private Instant lastTokenExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConferencingConnectionStatus status = ConferencingConnectionStatus.ACTIVE;

    @Column(name = "last_error_code", length = 255)
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Version
    @Column(nullable = false)
    private long version;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getProviderUserId() { return providerUserId; }
    public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    public String getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
    public void setRefreshTokenCiphertext(String refreshTokenCiphertext) { this.refreshTokenCiphertext = refreshTokenCiphertext; }
    public Instant getLastTokenExpiresAt() { return lastTokenExpiresAt; }
    public void setLastTokenExpiresAt(Instant lastTokenExpiresAt) { this.lastTokenExpiresAt = lastTokenExpiresAt; }
    public ConferencingConnectionStatus getStatus() { return status; }
    public void setStatus(ConferencingConnectionStatus status) { this.status = status; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public Instant getLastErrorAt() { return lastErrorAt; }
    public void setLastErrorAt(Instant lastErrorAt) { this.lastErrorAt = lastErrorAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
