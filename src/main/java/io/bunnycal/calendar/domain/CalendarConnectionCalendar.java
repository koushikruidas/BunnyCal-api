package io.bunnycal.calendar.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "calendar_connection_calendars", uniqueConstraints = {
        @UniqueConstraint(name = "uk_calendar_connection_calendars_connection_external", columnNames = {"connection_id", "external_calendar_id"})
})
public class CalendarConnectionCalendar extends BaseEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "external_calendar_id", nullable = false, length = 255)
    private String externalCalendarId;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "is_selected", nullable = false)
    private boolean selected;

    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled = true;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = true;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite = true;

    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    public UUID getId() { return id; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getExternalCalendarId() { return externalCalendarId; }
    public void setExternalCalendarId(String externalCalendarId) { this.externalCalendarId = externalCalendarId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public boolean isSyncEnabled() { return syncEnabled; }
    public void setSyncEnabled(boolean syncEnabled) { this.syncEnabled = syncEnabled; }
    public boolean isCanRead() { return canRead; }
    public void setCanRead(boolean canRead) { this.canRead = canRead; }
    public boolean isCanWrite() { return canWrite; }
    public void setCanWrite(boolean canWrite) { this.canWrite = canWrite; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
}
