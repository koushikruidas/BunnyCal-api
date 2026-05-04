package com.daedalussystems.easySchedule.calendar.domain;

import com.daedalussystems.easySchedule.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "calendar_connections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_calendar_connections_user_provider", columnNames = {"user_id", "provider"})
})
public class CalendarConnection extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CalendarProviderType provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "refresh_token_ciphertext", nullable = false, length = 4096)
    private String refreshTokenCiphertext;

    @Column(name = "access_token", length = 4096)
    private String accessToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "scopes", nullable = false, length = 1024)
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CalendarConnectionStatus status = CalendarConnectionStatus.ACTIVE;

    @Version
    @Column(nullable = false)
    private long version;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public CalendarProviderType getProvider() { return provider; }
    public void setProvider(CalendarProviderType provider) { this.provider = provider; }
    public String getProviderUserId() { return providerUserId; }
    public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    public String getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
    public void setRefreshTokenCiphertext(String refreshTokenCiphertext) { this.refreshTokenCiphertext = refreshTokenCiphertext; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public CalendarConnectionStatus getStatus() { return status; }
    public void setStatus(CalendarConnectionStatus status) { this.status = status; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
