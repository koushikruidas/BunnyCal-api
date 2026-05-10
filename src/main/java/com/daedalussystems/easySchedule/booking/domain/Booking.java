package com.daedalussystems.easySchedule.booking.domain;

import com.daedalussystems.easySchedule.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(BookingId.class)
@Table(
        name = "bookings",
        indexes = {
            @Index(name = "idx_bookings_host_start", columnList = "host_id,start_time"),
            @Index(name = "idx_bookings_event_type", columnList = "event_type_id")
        })
public class Booking extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Id
    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "event_type_id", nullable = false)
    private UUID eventTypeId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    @Column(name = "guest_name", length = 120)
    private String guestName;
}
