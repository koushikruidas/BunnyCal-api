package io.bunnycal.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An extra attendee the booker added at booking time, beyond the one named on the booking itself.
 *
 * <p>Guests are invite-only: they receive the calendar invite and every notification, but hold no
 * manage token and so cannot reschedule or cancel. {@code IcsInviteGenerator} emits each as an
 * ATTENDEE and {@code BookingNotificationService} mails each individually.
 *
 * <p>{@code bookingId} and {@code hostId} are plain columns rather than a {@code @ManyToOne} to
 * {@link Booking}. Booking is HASH-partitioned with a composite id, and mapping an association to
 * it drags in the composite key; {@code BookingQuestionAnswer} avoids the same trap the same way.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "booking_guests", indexes = {
    @Index(name = "idx_booking_guests_unique", columnList = "booking_id,host_id,guest_email", unique = true)
})
public class BookingGuest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    /** Denormalised so the composite FK to the partitioned bookings table resolves. */
    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    /** Stored already trimmed and lower-cased, so the unique index de-duplicates for real. */
    @Column(name = "guest_email", nullable = false, length = 255)
    private String guestEmail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
