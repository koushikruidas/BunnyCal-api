package io.bunnycal.booking.domain;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Composite key for the bookings table. host_id is a partition column,
// and PostgreSQL requires every unique/PK constraint on a partitioned
// table to include the partition key — so the entity-level identity
// must be (id, host_id), not just id.
//
// Field names MUST match the @Id-annotated fields on Booking exactly
// (id, hostId). JPA cross-references by name when @IdClass is used.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class BookingId implements Serializable {

    private UUID id;
    private UUID hostId;
}
