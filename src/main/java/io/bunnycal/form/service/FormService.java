package io.bunnycal.form.service;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.form.domain.Form;
import io.bunnycal.form.domain.FormQuestion;
import io.bunnycal.form.domain.FormQuestionOption;
import io.bunnycal.form.domain.QuestionType;
import io.bunnycal.form.dto.AnswerInput;
import io.bunnycal.form.dto.AnswerSnapshot;
import io.bunnycal.form.dto.FormQuestionOptionRequest;
import io.bunnycal.form.dto.FormQuestionOptionResponse;
import io.bunnycal.form.dto.FormRequest;
import io.bunnycal.form.dto.FormResponse;
import io.bunnycal.form.dto.QuestionRequest;
import io.bunnycal.form.dto.QuestionResponse;
import io.bunnycal.form.dto.ReorderQuestionsRequest;
import io.bunnycal.experience.domain.ExperienceStatus;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.repository.FormQuestionRepository;
import io.bunnycal.form.repository.FormRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FormService {

    private final FormRepository formRepository;
    private final FormQuestionRepository questionRepository;
    private final BookingExperienceRepository experienceRepository;

    public FormService(FormRepository formRepository, FormQuestionRepository questionRepository,
                       BookingExperienceRepository experienceRepository) {
        this.formRepository = formRepository;
        this.questionRepository = questionRepository;
        this.experienceRepository = experienceRepository;
    }

    public FormResponse createForm(UUID ownerId, FormRequest request) {
        Form form = Form.builder()
                .ownerId(ownerId)
                .name(request.name())
                .description(request.description())
                .build();
        form = formRepository.save(form);
        return toResponse(form, List.of());
    }

    @Transactional(readOnly = true)
    public List<FormResponse> getForms(UUID ownerId) {
        return formRepository.findByOwnerIdAndDeletedAtIsNull(ownerId).stream()
                .map(f -> toResponse(f, questionRepository.findByFormIdOrderBySortOrder(f.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public FormResponse getForm(UUID ownerId, UUID formId) {
        Form form = requireOwned(ownerId, formId);
        List<FormQuestion> questions = questionRepository.findByFormIdOrderBySortOrder(formId);
        return toResponse(form, questions);
    }

    public QuestionResponse addQuestion(UUID ownerId, UUID formId, QuestionRequest request) {
        Form form = requireOwned(ownerId, formId);
        List<FormQuestion> existing = questionRepository.findByFormIdOrderBySortOrder(formId);
        int nextOrder = existing.stream().mapToInt(FormQuestion::getSortOrder).max().orElse(-1) + 1;

        FormQuestion question = FormQuestion.builder()
                .formId(formId)
                .questionText(request.questionText())
                .questionType(request.questionType())
                .required(request.required())
                .sortOrder(nextOrder)
                .options(buildOptions(request.options()))
                .build();
        question = questionRepository.save(question);
        bumpVersion(form);
        return toQuestionResponse(question);
    }

    public QuestionResponse updateQuestion(UUID ownerId, UUID formId, UUID questionId, QuestionRequest request) {
        Form form = requireOwned(ownerId, formId);
        FormQuestion question = questionRepository.findByIdAndFormId(questionId, formId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        question.setQuestionText(request.questionText());
        question.setQuestionType(request.questionType());
        question.setRequired(request.required());
        question.getOptions().clear();
        question.getOptions().addAll(buildOptions(request.options()));
        question = questionRepository.save(question);
        bumpVersion(form);
        return toQuestionResponse(question);
    }

    public void deleteQuestion(UUID ownerId, UUID formId, UUID questionId) {
        Form form = requireOwned(ownerId, formId);
        FormQuestion question = questionRepository.findByIdAndFormId(questionId, formId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));
        questionRepository.delete(question);
        bumpVersion(form);
    }

    public void reorderQuestions(UUID ownerId, UUID formId, ReorderQuestionsRequest request) {
        Form form = requireOwned(ownerId, formId);
        List<FormQuestion> questions = questionRepository.findByFormIdOrderBySortOrder(formId);
        Map<UUID, FormQuestion> byId = new HashMap<>();
        questions.forEach(q -> byId.put(q.getId(), q));

        IntStream.range(0, request.orderedIds().size()).forEach(i -> {
            FormQuestion q = byId.get(request.orderedIds().get(i));
            if (q != null) q.setSortOrder(i);
        });
        questionRepository.saveAll(byId.values());
        bumpVersion(form);
    }

    public void deleteForm(UUID ownerId, UUID formId) {
        Form form = requireOwned(ownerId, formId);
        // Block deletion if the form is used by any DRAFT or ACTIVE experience.
        // ARCHIVED experiences are inert — no new bookings can occur through them.
        if (experienceRepository.existsByFormIdAndStatusNotAndDeletedAtIsNull(formId, ExperienceStatus.ARCHIVED)) {
            throw new CustomException(ErrorCode.FORM_ATTACHED_TO_ACTIVE_EXPERIENCE);
        }
        form.setDeletedAt(Instant.now());
        formRepository.save(form);
    }

    public List<AnswerSnapshot> validateAndSnapshotAnswers(UUID formId, List<AnswerInput> inputs) {
        List<FormQuestion> questions = questionRepository.findByFormIdOrderBySortOrder(formId);
        Map<UUID, FormQuestion> byId = new HashMap<>();
        questions.forEach(q -> byId.put(q.getId(), q));

        for (FormQuestion q : questions) {
            if (!q.isRequired()) continue;
            boolean answered = inputs != null && inputs.stream()
                    .anyMatch(a -> a.questionId().equals(q.getId()) && hasValue(a));
            if (!answered) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "Required question not answered: " + q.getQuestionText());
            }
        }

        List<AnswerSnapshot> snapshots = new ArrayList<>();
        if (inputs == null) return snapshots;
        for (AnswerInput input : inputs) {
            FormQuestion q = byId.get(input.questionId());
            if (q == null) continue;
            snapshots.add(new AnswerSnapshot(
                    q.getId(),
                    q.getQuestionText(),
                    q.getQuestionType().name(),
                    input.answerText(),
                    input.answerOptions()
            ));
        }
        return snapshots;
    }

    private boolean hasValue(AnswerInput a) {
        boolean hasText = a.answerText() != null && !a.answerText().isBlank();
        boolean hasOptions = a.answerOptions() != null && !a.answerOptions().isEmpty();
        return hasText || hasOptions;
    }

    private void bumpVersion(Form form) {
        form.setVersion(form.getVersion() + 1);
        formRepository.save(form);
    }

    private Form requireOwned(UUID ownerId, UUID formId) {
        return formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, formId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private List<FormQuestionOption> buildOptions(List<FormQuestionOptionRequest> requests) {
        if (requests == null) return new ArrayList<>();
        List<FormQuestionOption> options = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            FormQuestionOptionRequest r = requests.get(i);
            options.add(FormQuestionOption.builder()
                    .label(r.label())
                    .value(r.value())
                    .sortOrder(i)
                    .build());
        }
        return options;
    }

    private FormResponse toResponse(Form form, List<FormQuestion> questions) {
        return new FormResponse(
                form.getId(),
                form.getName(),
                form.getDescription(),
                form.getVersion(),
                questions.stream().map(this::toQuestionResponse).toList()
        );
    }

    private QuestionResponse toQuestionResponse(FormQuestion q) {
        List<FormQuestionOptionResponse> opts = q.getOptions().stream()
                .map(o -> new FormQuestionOptionResponse(o.getId(), o.getLabel(), o.getValue(), o.getSortOrder()))
                .toList();
        return new QuestionResponse(q.getId(), q.getQuestionText(), q.getQuestionType(),
                q.isRequired(), q.getSortOrder(), opts);
    }
}
