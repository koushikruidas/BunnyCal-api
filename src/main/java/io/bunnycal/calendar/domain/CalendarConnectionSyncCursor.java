package io.bunnycal.calendar.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "calendar_connection_sync_cursors", uniqueConstraints = {
        @UniqueConstraint(name = "uk_calendar_connection_sync_cursors_connection_calendar",
                columnNames = {"connection_id", "external_calendar_id"})
})
public class CalendarConnectionSyncCursor extends BaseEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "external_calendar_id", nullable = false, length = 512)
    private String externalCalendarId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CalendarProviderType provider;

    @Column(name = "delta_cursor", columnDefinition = "TEXT")
    private String deltaCursor;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    public UUID getId() { return id; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getExternalCalendarId() { return externalCalendarId; }
    public void setExternalCalendarId(String externalCalendarId) { this.externalCalendarId = externalCalendarId; }
    public CalendarProviderType getProvider() { return provider; }
    public void setProvider(CalendarProviderType provider) { this.provider = provider; }
    public String getDeltaCursor() { return deltaCursor; }
    public void setDeltaCursor(String deltaCursor) { this.deltaCursor = deltaCursor; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
}
