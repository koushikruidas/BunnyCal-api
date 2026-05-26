package io.bunnycal.booking.ownership;

import io.bunnycal.calendar.domain.CalendarProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_ownership")
public class BookingOwnership {

    @Id
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "organizer_authority", nullable = false, length = 32)
    private String organizerAuthority;

    @Enumerated(EnumType.STRING)
    @Column(name = "projection_provider", length = 32)
    private CalendarProviderType projectionProvider;

    @Column(name = "projection_connection_id")
    private UUID projectionConnectionId;

    @Column(name = "projection_calendar_id", length = 255)
    private String projectionCalendarId;

    @Column(name = "provider_external_event_id", length = 255)
    private String providerExternalEventId;

    @Column(name = "ownership_version", nullable = false)
    private long ownershipVersion = 1L;

    @Column(name = "ownership_state", nullable = false, length = 32)
    private String ownershipState = "RESOLVED";

    @Column(name = "ambiguity_reason", length = 128)
    private String ambiguityReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public String getOrganizerAuthority() { return organizerAuthority; }
    public void setOrganizerAuthority(String organizerAuthority) { this.organizerAuthority = organizerAuthority; }
    public CalendarProviderType getProjectionProvider() { return projectionProvider; }
    public void setProjectionProvider(CalendarProviderType projectionProvider) { this.projectionProvider = projectionProvider; }
    public UUID getProjectionConnectionId() { return projectionConnectionId; }
    public void setProjectionConnectionId(UUID projectionConnectionId) { this.projectionConnectionId = projectionConnectionId; }
    public String getProjectionCalendarId() { return projectionCalendarId; }
    public void setProjectionCalendarId(String projectionCalendarId) { this.projectionCalendarId = projectionCalendarId; }
    public String getProviderExternalEventId() { return providerExternalEventId; }
    public void setProviderExternalEventId(String providerExternalEventId) { this.providerExternalEventId = providerExternalEventId; }
    public long getOwnershipVersion() { return ownershipVersion; }
    public void setOwnershipVersion(long ownershipVersion) { this.ownershipVersion = ownershipVersion; }
    public String getOwnershipState() { return ownershipState; }
    public void setOwnershipState(String ownershipState) { this.ownershipState = ownershipState; }
    public String getAmbiguityReason() { return ambiguityReason; }
    public void setAmbiguityReason(String ambiguityReason) { this.ambiguityReason = ambiguityReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
