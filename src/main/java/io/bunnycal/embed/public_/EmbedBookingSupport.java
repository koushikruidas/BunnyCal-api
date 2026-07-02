package io.bunnycal.embed.public_;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.domain.ExperienceStatus;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.dto.AnswerInput;
import io.bunnycal.form.dto.AnswerSnapshot;
import io.bunnycal.form.service.FormService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles embed-specific pre/post logic around booking holds:
 * 1. Verifies the embed token (BEFORE booking creation)
 * 2. Validates and snapshots form answers (BEFORE booking creation)
 * 3. Persists snapshots (AFTER booking creation, same transaction)
 */
@Service
public class EmbedBookingSupport {

    private static final Logger log = LoggerFactory.getLogger(EmbedBookingSupport.class);

    private final EmbedTokenService embedTokenService;
    private final BookingExperienceRepository experienceRepository;
    private final FormService formService;
    private final BookingQuestionAnswerRepository answerRepository;
    private final ObjectMapper objectMapper;

    public EmbedBookingSupport(EmbedTokenService embedTokenService,
                               BookingExperienceRepository experienceRepository,
                               FormService formService,
                               BookingQuestionAnswerRepository answerRepository,
                               ObjectMapper objectMapper) {
        this.embedTokenService = embedTokenService;
        this.experienceRepository = experienceRepository;
        this.formService = formService;
        this.answerRepository = answerRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Validate embed token and form answers. Throws before any state mutation.
     * Returns snapshots (may be empty if no form) to be persisted after booking is created.
     */
    public List<AnswerSnapshot> validateEmbedRequest(String embedToken, List<AnswerInput> answers) {
        EmbedTokenClaims claims = embedTokenService.verify(embedToken);

        var experience = experienceRepository.findById(claims.experienceId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        if (experience.getStatus() != ExperienceStatus.ACTIVE || experience.getDeletedAt() != null) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        if (experience.getVersion() != claims.experienceVersion()) {
            log.debug("embed_version_mismatch experienceId={} expected={} got={}",
                    experience.getId(), experience.getVersion(), claims.experienceVersion());
            throw new CustomException(ErrorCode.EXPERIENCE_VERSION_MISMATCH);
        }

        UUID formId = experience.getFormId();
        if (formId == null) {
            return List.of();
        }

        return formService.validateAndSnapshotAnswers(formId, answers);
    }

    @Transactional
    public void persistAnswers(UUID bookingId, UUID hostId, List<AnswerSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        List<BookingQuestionAnswer> rows = new java.util.ArrayList<>();
        for (AnswerSnapshot s : snapshots) {
            String answerJson = null;
            if (s.answerOptions() != null && !s.answerOptions().isEmpty()) {
                try {
                    answerJson = objectMapper.writeValueAsString(s.answerOptions());
                } catch (Exception ex) {
                    log.warn("Failed to serialize answerOptions for questionId={}", s.questionId(), ex);
                }
            }
            rows.add(BookingQuestionAnswer.builder()
                    .bookingId(bookingId)
                    .hostId(hostId)
                    .questionId(s.questionId())
                    .questionLabelSnapshot(s.questionLabelSnapshot())
                    .questionTypeSnapshot(s.questionTypeSnapshot())
                    .answerValue(s.answerValue())
                    .answerJson(answerJson)
                    .build());
        }
        answerRepository.saveAll(rows);
    }
}
