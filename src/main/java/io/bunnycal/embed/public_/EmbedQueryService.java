package io.bunnycal.embed.public_;

import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.Feature;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.domain.BookingExperience;
import io.bunnycal.experience.domain.ExperienceStatus;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.domain.FormQuestion;
import io.bunnycal.form.dto.FormQuestionOptionResponse;
import io.bunnycal.form.dto.QuestionResponse;
import io.bunnycal.form.repository.FormQuestionRepository;
import io.bunnycal.form.repository.FormRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EmbedQueryService {

    private final BookingExperienceRepository experienceRepository;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final PublicBookingService publicBookingService;
    private final FormRepository formRepository;
    private final FormQuestionRepository questionRepository;
    private final EmbedTokenService embedTokenService;
    private final EntitlementService entitlementService;

    public EmbedQueryService(BookingExperienceRepository experienceRepository,
                             UserRepository userRepository,
                             EventTypeRepository eventTypeRepository,
                             PublicBookingService publicBookingService,
                             FormRepository formRepository,
                             FormQuestionRepository questionRepository,
                             EmbedTokenService embedTokenService,
                             EntitlementService entitlementService) {
        this.experienceRepository = experienceRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.publicBookingService = publicBookingService;
        this.formRepository = formRepository;
        this.questionRepository = questionRepository;
        this.embedTokenService = embedTokenService;
        this.entitlementService = entitlementService;
    }

    public PublicEmbedConfigResponse getEmbedConfig(String experienceSlug) {
        BookingExperience experience = experienceRepository.findBySlugAndDeletedAtIsNull(experienceSlug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));

        if (experience.getStatus() != ExperienceStatus.ACTIVE) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        // An active experience whose owner is no longer entitled to Experiences is inactive to
        // the public — treat as not-found (same neutral response as a non-ACTIVE experience), so
        // no billing/subscription state leaks to the embed visitor (Principle 9).
        if (!entitlementService.resolve(experience.getOwnerId()).has(Feature.EXPERIENCES)) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        String username = userRepository.findById(experience.getOwnerId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND))
                .getUsername();

        String eventTypeSlug = eventTypeRepository.findById(experience.getEventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND))
                .getSlug();

        var eventInfo = publicBookingService.eventInfo(username, eventTypeSlug);

        List<QuestionResponse> form = null;
        long formVersion = 0L;
        if (experience.getFormId() != null) {
            var formEntity = formRepository.findById(experience.getFormId()).orElse(null);
            if (formEntity != null && formEntity.getDeletedAt() == null) {
                formVersion = formEntity.getVersion();
                List<FormQuestion> questions = questionRepository.findByFormIdOrderBySortOrder(formEntity.getId());
                form = questions.stream().map(this::toQuestionResponse).toList();
            }
        }

        String embedToken = embedTokenService.mint(experience.getId(), experience.getVersion(), formVersion);

        return new PublicEmbedConfigResponse(
                eventInfo, form, experience.getPrimaryColor(), experience.isShowBranding(), embedToken, eventTypeSlug);
    }

    private QuestionResponse toQuestionResponse(FormQuestion q) {
        List<FormQuestionOptionResponse> opts = q.getOptions().stream()
                .map(o -> new FormQuestionOptionResponse(o.getId(), o.getLabel(), o.getValue(), o.getSortOrder()))
                .toList();
        return new QuestionResponse(q.getId(), q.getQuestionText(), q.getQuestionType(),
                q.isRequired(), q.getSortOrder(), opts);
    }
}
