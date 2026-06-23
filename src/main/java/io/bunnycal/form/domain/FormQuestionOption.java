package io.bunnycal.form.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
@Table(name = "form_question_options", indexes = {
    @Index(name = "idx_fqo_question", columnList = "question_id")
})
public class FormQuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String label;

    @Column(nullable = false, length = 100)
    private String value;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
