package io.bunnycal.experience.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.domain.BookingExperience;
import io.bunnycal.experience.domain.ExperienceStatus;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.domain.Form;
import io.bunnycal.form.repository.FormRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers Fix 2: activating an experience (DRAFT or ARCHIVED -> ACTIVE) must re-validate
 * that its event type and form still exist and are owned, so reactivation can't publish
 * an embed that 404s or silently drops its questionnaire.
 */
@ExtendWith(MockitoExtension.class)
class BookingExperienceActivateTest {

    @Mock private BookingExperienceRepository experienceRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private FormRepository formRepository;

    private BookingExperienceService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID experienceId = UUID.randomUUID();
    private final UUID eventTypeId = UUID.randomUUID();
    private final UUID formId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingExperienceService(experienceRepository, eventTypeRepository, formRepository);
    }

    private BookingExperience archivedExperience(UUID attachedFormId) {
        BookingExperience experience = BookingExperience.builder()
                .ownerId(ownerId)
                .name("Enterprise Demo")
                .slug("enterprise-demo")
                .eventTypeId(eventTypeId)
                .formId(attachedFormId)
                .status(ExperienceStatus.ARCHIVED)
                .build();
        experience.setId(experienceId);
        return experience;
    }

    @Test
    void activate_succeedsWhenEventTypeAndFormStillExist() {
        BookingExperience experience = archivedExperience(formId);
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));
        when(eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, ownerId))
                .thenReturn(Optional.of(new EventType()));
        when(formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, formId))
                .thenReturn(Optional.of(new Form()));
        when(experienceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activateExperience(ownerId, experienceId);

        assertEquals(ExperienceStatus.ACTIVE, experience.getStatus());
        verify(experienceRepository).save(experience);
    }

    @Test
    void activate_succeedsWithNoFormAttached() {
        BookingExperience experience = archivedExperience(null);
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));
        when(eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, ownerId))
                .thenReturn(Optional.of(new EventType()));
        when(experienceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activateExperience(ownerId, experienceId);

        assertEquals(ExperienceStatus.ACTIVE, experience.getStatus());
        verify(formRepository, never()).findByOwnerIdAndIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    void activate_blockedWhenEventTypeDeleted() {
        BookingExperience experience = archivedExperience(formId);
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));
        when(eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, ownerId))
                .thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> service.activateExperience(ownerId, experienceId));

        assertEquals(ErrorCode.EXPERIENCE_NOT_ACTIVATABLE, ex.getErrorCode());
        assertEquals(ExperienceStatus.ARCHIVED, experience.getStatus(), "status must not change when blocked");
        verify(experienceRepository, never()).save(any());
    }

    @Test
    void activate_blockedWhenAttachedFormDeleted() {
        BookingExperience experience = archivedExperience(formId);
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));
        when(eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, ownerId))
                .thenReturn(Optional.of(new EventType()));
        when(formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, formId))
                .thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> service.activateExperience(ownerId, experienceId));

        assertEquals(ErrorCode.EXPERIENCE_NOT_ACTIVATABLE, ex.getErrorCode());
        assertEquals(ExperienceStatus.ARCHIVED, experience.getStatus());
        verify(experienceRepository, never()).save(any());
    }
}
