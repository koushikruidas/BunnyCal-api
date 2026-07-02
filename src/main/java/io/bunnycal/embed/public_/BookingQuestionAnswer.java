package io.bunnycal.embed.public_;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "booking_question_answers", indexes = {
    @Index(name = "idx_bqa_booking", columnList = "booking_id,host_id"),
    @Index(name = "idx_bqa_question", columnList = "question_id")
})
public class BookingQuestionAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "question_label_snapshot", nullable = false, columnDefinition = "TEXT")
    private String questionLabelSnapshot;

    @Column(name = "question_type_snapshot", nullable = false, length = 32)
    private String questionTypeSnapshot;

    @Column(name = "answer_value", columnDefinition = "TEXT")
    private String answerValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_json", columnDefinition = "JSONB")
    private String answerJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
