package io.bunnycal.embed.public_;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.domain.FormQuestion;
import io.bunnycal.form.domain.QuestionType;
import io.bunnycal.form.dto.AnswerInput;
import io.bunnycal.form.dto.AnswerSnapshot;
import io.bunnycal.form.repository.FormQuestionRepository;
import io.bunnycal.form.repository.FormRepository;
import io.bunnycal.form.service.FormService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the core data-retention guarantee: questionnaire answers are snapshotted onto
 * the booking with the question label/type captured at booking time, with NO foreign key
 * back to the form or its questions. Deleting (or even hard-deleting) the form afterwards
 * therefore cannot erase historical answers.
 *
 * The test proves this by showing the persisted {@link BookingQuestionAnswer} rows carry
 * the label/type that came from the snapshot taken at booking time — not values re-read
 * from the (now-gone) form.
 */
@ExtendWith(MockitoExtension.class)
class AnswerSnapshotRetentionTest {

    // --- snapshotting (FormService) ---
    @Mock private FormRepository formRepository;
    @Mock private FormQuestionRepository questionRepository;
    @Mock private BookingExperienceRepository experienceRepository;
    @Mock private io.bunnycal.billing.entitlement.EntitlementService entitlementService;
    private FormService formService;

    // --- persistence (EmbedBookingSupport) ---
    @Mock private EmbedTokenService embedTokenService;
    @Mock private BookingQuestionAnswerRepository answerRepository;
    private EmbedBookingSupport embedBookingSupport;

    private final UUID formId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID textQId = UUID.randomUUID();
    private final UUID selectQId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        formService = new FormService(formRepository, questionRepository, experienceRepository, entitlementService);
        embedBookingSupport = new EmbedBookingSupport(
                embedTokenService, experienceRepository, formService, answerRepository, new ObjectMapper());
    }

    private FormQuestion question(UUID id, String text, QuestionType type, boolean required) {
        FormQuestion q = FormQuestion.builder()
                .formId(formId)
                .questionText(text)
                .questionType(type)
                .required(required)
                .build();
        q.setId(id);
        return q;
    }

    @Test
    void answersSurviveFormDeletion_labelAndTypeAreSnapshottedInline() {
        when(questionRepository.findByFormIdOrderBySortOrder(formId)).thenReturn(List.of(
                question(textQId, "What is your company name?", QuestionType.SHORT_TEXT, true),
                question(selectQId, "Team size?", QuestionType.SINGLE_SELECT, false)
        ));

        List<AnswerInput> inputs = List.of(
                new AnswerInput(textQId, "Acme Corp", null),
                new AnswerInput(selectQId, null, List.of("11-50"))
        );

        // Step 1: snapshot at booking time — labels/types are captured into the snapshot.
        List<AnswerSnapshot> snapshots = formService.validateAndSnapshotAnswers(formId, inputs);
        assertEquals(2, snapshots.size());

        // Step 2: persist onto the booking.
        embedBookingSupport.persistAnswers(bookingId, hostId, snapshots);

        ArgumentCaptor<List<BookingQuestionAnswer>> captor = ArgumentCaptor.forClass(List.class);
        verify(answerRepository).saveAll(captor.capture());
        Map<UUID, BookingQuestionAnswer> byQuestion = captor.getValue().stream()
                .collect(Collectors.toMap(BookingQuestionAnswer::getQuestionId, r -> r));

        BookingQuestionAnswer textRow = byQuestion.get(textQId);
        // The label/type are stored on the row itself — independent of the form, which may
        // be deleted later. This is what makes the answers durable.
        assertEquals("What is your company name?", textRow.getQuestionLabelSnapshot());
        assertEquals("SHORT_TEXT", textRow.getQuestionTypeSnapshot());
        assertEquals("Acme Corp", textRow.getAnswerValue());
        assertEquals(bookingId, textRow.getBookingId());
        assertEquals(hostId, textRow.getHostId());

        BookingQuestionAnswer selectRow = byQuestion.get(selectQId);
        assertEquals("Team size?", selectRow.getQuestionLabelSnapshot());
        assertEquals("SINGLE_SELECT", selectRow.getQuestionTypeSnapshot());
        assertTrue(selectRow.getAnswerJson().contains("11-50"), "multi-value answer is serialized to JSON");
    }

    @Test
    void persistAnswers_noopWhenNoSnapshots() {
        embedBookingSupport.persistAnswers(bookingId, hostId, List.of());
        verify(answerRepository, org.mockito.Mockito.never()).saveAll(any());
    }
}
