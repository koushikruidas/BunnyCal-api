package io.bunnycal.embed.public_;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingQuestionAnswerRepository extends JpaRepository<BookingQuestionAnswer, UUID> {

    List<BookingQuestionAnswer> findByBookingIdAndHostId(UUID bookingId, UUID hostId);

    /**
     * Clears a booking's answers so they can be written again. Needed because the details step is
     * re-submittable — a guest can go back, change an answer and submit against the same held
     * booking — and answers are saved, not upserted, so without this the rows would accumulate.
     */
    void deleteByBookingIdAndHostId(UUID bookingId, UUID hostId);

    List<BookingQuestionAnswer> findByBookingIdInAndHostId(List<UUID> bookingIds, UUID hostId);
}
