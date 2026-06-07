package io.bunnycal.calendar.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "calendar_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_calendar_events_connection_provider_external",
                        columnNames = {"connection_id", "provider", "external_event_id"})
        },
        indexes = {
                @Index(name = "idx_calendar_events_connection_start", columnList = "connection_id, starts_at"),
                @Index(name = "idx_calendar_events_user_start", columnList = "user_id, starts_at")
        })
public class CalendarEvent extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "external_event_id", nullable = false, length = 255)
    private String externalEventId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    /**
     * Provider-native calendar id this event was ingested from (e.g. a Microsoft
     * Graph calendar id). Null on rows ingested before multi-calendar sync
     * landed and on providers that have not yet been migrated to per-calendar
     * attribution.
     */
    @Column(name = "external_calendar_id", length = 512)
    private String externalCalendarId;

    @Column(name = "title", length = 1024)
    private String title;

    @Column(name = "location", length = 1024)
    private String location;

    @Column(name = "organizer_email", length = 320)
    private String organizerEmail;

    /**
     * Session projection rows may exist on a calendar that is also used for
     * availability selection, but they must not block slots until the session
     * itself reaches FULL. This flag lets the busy-time pipeline distinguish
     * actual conflicts from projection echoes.
     */
    @Column(name = "blocks_availability", nullable = false)
    private boolean blocksAvailability = true;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public String getExternalCalendarId() { return externalCalendarId; }
    public void setExternalCalendarId(String externalCalendarId) { this.externalCalendarId = externalCalendarId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getOrganizerEmail() { return organizerEmail; }
    public void setOrganizerEmail(String organizerEmail) { this.organizerEmail = organizerEmail; }
    public boolean isBlocksAvailability() { return blocksAvailability; }
    public void setBlocksAvailability(boolean blocksAvailability) { this.blocksAvailability = blocksAvailability; }
}
