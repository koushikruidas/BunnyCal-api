package io.bunnycal.conferencing.domain;

import io.bunnycal.common.audit.BaseEntity;
import io.bunnycal.common.enums.ConferencingProviderType;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "conferencing_event_mappings", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conferencing_event_mappings_booking_provider", columnNames = {"booking_id", "provider"})
})
public class ConferencingEventMapping extends BaseEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ConferencingProviderType provider;

    @Column(name = "meeting_id", length = 255)
    private String meetingId;

    @Column(name = "join_url", length = 2048)
    private String joinUrl;

    @Column(name = "host_url", length = 2048)
    private String hostUrl;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "last_error", length = 255)
    private String lastError;

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public ConferencingProviderType getProvider() { return provider; }
    public void setProvider(ConferencingProviderType provider) { this.provider = provider; }
    public String getMeetingId() { return meetingId; }
    public void setMeetingId(String meetingId) { this.meetingId = meetingId; }
    public String getJoinUrl() { return joinUrl; }
    public void setJoinUrl(String joinUrl) { this.joinUrl = joinUrl; }
    public String getHostUrl() { return hostUrl; }
    public void setHostUrl(String hostUrl) { this.hostUrl = hostUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
