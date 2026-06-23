package io.bunnycal.embed.public_;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingQuestionAnswerRepository extends JpaRepository<BookingQuestionAnswer, UUID> {

    List<BookingQuestionAnswer> findByBookingIdAndHostId(UUID bookingId, UUID hostId);
}
