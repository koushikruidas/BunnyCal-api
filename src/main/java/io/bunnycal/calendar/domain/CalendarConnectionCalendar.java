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

    /**
     * What kind of calendar this is — {@link CalendarRole}. Decides display, blocking, and sync:
     * only {@code PRIMARY} is shown and blocks by default; {@code HOLIDAY} feeds days-off (never busy
     * time); {@code OTHER} (birthdays, feeds) does nothing. Classified at hydration.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "calendar_role", nullable = false, length = 16)
    private CalendarRole calendarRole = CalendarRole.OTHER;

    /**
     * This is the calendar within its connection that receives confirmed bookings. At most one per
     * connection.
     *
     * <p>Before the global model this column was read by the write-back resolver but written by
     * nothing, so it was always false and the resolver's "selected calendar" tier could never fire.
     * The settings page now writes it.
     */
    @Column(name = "is_selected", nullable = false)
    private boolean selected;

    /**
     * The primary calendar is read for free/busy whenever anyone books its owner — on their own
     * event types and on any team event they participate in. Holiday and other calendars stay off.
     *
     * <p>Turning it off is the deliberate exception, and it holds everywhere. Under the old
     * per-event-type model a user could exclude a noisy calendar from their own events, then have it
     * silently start blocking them again the moment a colleague added them to a round-robin.
     */
    @Column(name = "checks_availability", nullable = false)
    private boolean checksAvailability;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = true;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite = true;

    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    /** True only when Microsoft Graph advertises teamsForBusiness for this exact calendar. */
    @Column(name = "supports_native_teams")
    private Boolean supportsNativeTeams;

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

    public CalendarRole getCalendarRole() { return calendarRole; }
    public void setCalendarRole(CalendarRole calendarRole) { this.calendarRole = calendarRole; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public boolean isChecksAvailability() { return checksAvailability; }
    public void setChecksAvailability(boolean checksAvailability) { this.checksAvailability = checksAvailability; }
    public boolean isCanRead() { return canRead; }
    public void setCanRead(boolean canRead) { this.canRead = canRead; }
    public boolean isCanWrite() { return canWrite; }
    public void setCanWrite(boolean canWrite) { this.canWrite = canWrite; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isSupportsNativeTeams() { return Boolean.TRUE.equals(supportsNativeTeams); }
    public boolean isTeamsCapabilityKnown() { return supportsNativeTeams != null; }
    public void setSupportsNativeTeams(boolean supportsNativeTeams) { this.supportsNativeTeams = supportsNativeTeams; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
}
