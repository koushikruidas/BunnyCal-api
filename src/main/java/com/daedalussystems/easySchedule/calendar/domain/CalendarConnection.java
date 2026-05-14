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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "last_token_expires_at", nullable = false)
    private Instant lastTokenExpiresAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scopes", nullable = false, columnDefinition = "text[]")
    private List<String> scopes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CalendarConnectionStatus status = CalendarConnectionStatus.ACTIVE;

    @Column(name = "last_error_code", length = 255)
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "provider_sync_cursor", length = 2048)
    private String providerSyncCursor;

    @Column(name = "provider_cursor_updated_at")
    private Instant providerCursorUpdatedAt;

    @Column(name = "provider_cursor_invalidated_at")
    private Instant providerCursorInvalidatedAt;

    @Column(name = "webhook_channel_id", length = 255)
    private String webhookChannelId;

    @Column(name = "webhook_resource_id", length = 255)
    private String webhookResourceId;

    @Column(name = "webhook_channel_expires_at")
    private Instant webhookChannelExpiresAt;

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
    public Instant getLastTokenExpiresAt() { return lastTokenExpiresAt; }
    public void setLastTokenExpiresAt(Instant lastTokenExpiresAt) { this.lastTokenExpiresAt = lastTokenExpiresAt; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public CalendarConnectionStatus getStatus() { return status; }
    public void setStatus(CalendarConnectionStatus status) { this.status = status; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public Instant getLastErrorAt() { return lastErrorAt; }
    public void setLastErrorAt(Instant lastErrorAt) { this.lastErrorAt = lastErrorAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getProviderSyncCursor() { return providerSyncCursor; }
    public void setProviderSyncCursor(String providerSyncCursor) { this.providerSyncCursor = providerSyncCursor; }
    public Instant getProviderCursorUpdatedAt() { return providerCursorUpdatedAt; }
    public void setProviderCursorUpdatedAt(Instant providerCursorUpdatedAt) { this.providerCursorUpdatedAt = providerCursorUpdatedAt; }
    public Instant getProviderCursorInvalidatedAt() { return providerCursorInvalidatedAt; }
    public void setProviderCursorInvalidatedAt(Instant providerCursorInvalidatedAt) { this.providerCursorInvalidatedAt = providerCursorInvalidatedAt; }
    public String getWebhookChannelId() { return webhookChannelId; }
    public void setWebhookChannelId(String webhookChannelId) { this.webhookChannelId = webhookChannelId; }
    public String getWebhookResourceId() { return webhookResourceId; }
    public void setWebhookResourceId(String webhookResourceId) { this.webhookResourceId = webhookResourceId; }
    public Instant getWebhookChannelExpiresAt() { return webhookChannelExpiresAt; }
    public void setWebhookChannelExpiresAt(Instant webhookChannelExpiresAt) { this.webhookChannelExpiresAt = webhookChannelExpiresAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
