package io.bunnycal.experience.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.domain.BookingExperience;
import io.bunnycal.experience.domain.ExperienceStatus;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.repository.FormRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingExperienceDeleteGuardTest {

    @Mock private BookingExperienceRepository experienceRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private FormRepository formRepository;
    @Mock private io.bunnycal.billing.entitlement.EntitlementService entitlementService;

    private BookingExperienceService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID experienceId = UUID.randomUUID();
    private final UUID eventTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingExperienceService(experienceRepository, eventTypeRepository, formRepository,
                entitlementService);
    }

    private BookingExperience experience(ExperienceStatus status) {
        BookingExperience experience = BookingExperience.builder()
                .ownerId(ownerId)
                .name("Embedded Demo")
                .slug("embedded-demo")
                .eventTypeId(eventTypeId)
                .status(status)
                .build();
        experience.setId(experienceId);
        return experience;
    }

    @Test
    void delete_softDeletesArchivedExperience() {
        BookingExperience experience = experience(ExperienceStatus.ARCHIVED);
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));

        service.deleteExperience(ownerId, experienceId);

        ArgumentCaptor<BookingExperience> saved = ArgumentCaptor.forClass(BookingExperience.class);
        verify(experienceRepository).save(saved.capture());
        assertNotNull(saved.getValue().getDeletedAt());
    }

    @Test
    void delete_blockedWhileExperienceIsActive() {
        BookingExperience experience = experience(ExperienceStatus.ACTIVE);
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));

        CustomException ex = assertThrows(CustomException.class, () -> service.deleteExperience(ownerId, experienceId));

        assertEquals(ErrorCode.ACTIVE_EXPERIENCE_DELETE_REQUIRES_ARCHIVE, ex.getErrorCode());
        assertNull(experience.getDeletedAt());
        verify(experienceRepository, never()).save(any());
    }

    @Test
    void delete_notFoundWhenNotOwned() {
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> service.deleteExperience(ownerId, experienceId));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
