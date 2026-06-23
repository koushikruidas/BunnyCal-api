package io.bunnycal.experience.service;

import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.domain.BookingExperience;
import io.bunnycal.experience.domain.ExperienceStatus;
import io.bunnycal.experience.dto.BookingExperienceRequest;
import io.bunnycal.experience.dto.BookingExperienceResponse;
import io.bunnycal.experience.dto.CreateExperienceRequest;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.repository.FormRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BookingExperienceService {

    private final BookingExperienceRepository experienceRepository;
    private final EventTypeRepository eventTypeRepository;
    private final FormRepository formRepository;

    public BookingExperienceService(BookingExperienceRepository experienceRepository,
                                    EventTypeRepository eventTypeRepository,
                                    FormRepository formRepository) {
        this.experienceRepository = experienceRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.formRepository = formRepository;
    }

    public BookingExperienceResponse createExperience(UUID ownerId, CreateExperienceRequest request) {
        eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(request.eventTypeId(), ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Event type not found or does not belong to this account."));

        if (request.formId() != null) {
            formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, request.formId())
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                            "Form not found or does not belong to this account."));
        }

        String slug = generateSlug(request.name());

        BookingExperience experience = BookingExperience.builder()
                .ownerId(ownerId)
                .name(request.name())
                .slug(slug)
                .eventTypeId(request.eventTypeId())
                .formId(request.formId())
                .primaryColor(request.primaryColor())
                .showBranding(request.showBranding())
                .build();
        experience = experienceRepository.save(experience);
        return toResponse(experience);
    }

    @Transactional(readOnly = true)
    public List<BookingExperienceResponse> getExperiences(UUID ownerId) {
        return experienceRepository.findByOwnerIdAndDeletedAtIsNull(ownerId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingExperienceResponse getExperience(UUID ownerId, UUID experienceId) {
        return toResponse(requireOwned(ownerId, experienceId));
    }

    public BookingExperienceResponse updateExperience(UUID ownerId, UUID experienceId,
                                                      BookingExperienceRequest request) {
        BookingExperience experience = requireOwned(ownerId, experienceId);

        if (request.formId() != null) {
            formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, request.formId())
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                            "Form not found or does not belong to this account."));
        }

        experience.setName(request.name());
        experience.setFormId(request.formId());
        experience.setPrimaryColor(request.primaryColor());
        experience.setShowBranding(request.showBranding());
        experience.setVersion(experience.getVersion() + 1);
        return toResponse(experienceRepository.save(experience));
    }

    public BookingExperienceResponse activateExperience(UUID ownerId, UUID experienceId) {
        BookingExperience experience = requireOwned(ownerId, experienceId);
        experience.setStatus(ExperienceStatus.ACTIVE);
        return toResponse(experienceRepository.save(experience));
    }

    public BookingExperienceResponse archiveExperience(UUID ownerId, UUID experienceId) {
        BookingExperience experience = requireOwned(ownerId, experienceId);
        experience.setStatus(ExperienceStatus.ARCHIVED);
        return toResponse(experienceRepository.save(experience));
    }

    public void deleteExperience(UUID ownerId, UUID experienceId) {
        BookingExperience experience = requireOwned(ownerId, experienceId);
        experience.setDeletedAt(Instant.now());
        experienceRepository.save(experience);
    }

    public String getEmbedSnippet(UUID ownerId, UUID experienceId) {
        BookingExperience experience = requireOwned(ownerId, experienceId);
        return String.format(
                "<script src=\"https://app.bunnycal.io/widget.js\"></script>\n" +
                "<div id=\"bunnycal-widget\"></div>\n" +
                "<script>\n" +
                "  BunnyCal.init({ experienceSlug: '%s', containerId: 'bunnycal-widget' });\n" +
                "</script>",
                experience.getSlug());
    }

    private BookingExperience requireOwned(UUID ownerId, UUID experienceId) {
        return experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private String generateSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "experience";

        String slug = base;
        int attempts = 0;
        while (experienceRepository.findBySlugAndDeletedAtIsNull(slug).isPresent()) {
            slug = base + "-" + Integer.toHexString((int) (Math.random() * 0xffff));
            if (++attempts > 10) slug = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return slug;
    }

    private BookingExperienceResponse toResponse(BookingExperience e) {
        return new BookingExperienceResponse(
                e.getId(), e.getName(), e.getSlug(), e.getEventTypeId(), e.getFormId(),
                e.getPrimaryColor(), e.isShowBranding(), e.getStatus(), e.getVersion());
    }
}
