package com.daedalussystems.easySchedule.calendar.domain;

import com.daedalussystems.easySchedule.common.audit.BaseEntity;
import jakarta.persistence.*;
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
}
