package io.bunnycal.form.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.domain.Form;
import io.bunnycal.form.repository.FormQuestionRepository;
import io.bunnycal.form.repository.FormRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers Fix 1: permanent form deletion must be blocked while the form is referenced by
 * ANY non-deleted experience, including ARCHIVED ones (which can be reactivated).
 */
@ExtendWith(MockitoExtension.class)
class FormServiceDeleteTest {

    @Mock private FormRepository formRepository;
    @Mock private FormQuestionRepository questionRepository;
    @Mock private BookingExperienceRepository experienceRepository;
    @Mock private io.bunnycal.billing.entitlement.EntitlementService entitlementService;

    private FormService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID formId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FormService(formRepository, questionRepository, experienceRepository, entitlementService);
    }

    private Form ownedForm() {
        Form form = Form.builder().ownerId(ownerId).name("Sales Qualification Form").build();
        form.setId(formId);
        return form;
    }

    @Test
    void deleteForm_softDeletesWhenNoExperienceReferencesIt() {
        Form form = ownedForm();
        when(formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, formId))
                .thenReturn(Optional.of(form));
        when(experienceRepository.existsByFormIdAndDeletedAtIsNull(formId)).thenReturn(false);

        service.deleteForm(ownerId, formId);

        ArgumentCaptor<Form> saved = ArgumentCaptor.forClass(Form.class);
        verify(formRepository).save(saved.capture());
        assertNotNull(saved.getValue().getDeletedAt(), "form should be soft-deleted");
    }

    @Test
    void deleteForm_blockedWhenReferencedByAnyLiveExperienceIncludingArchived() {
        Form form = ownedForm();
        when(formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, formId))
                .thenReturn(Optional.of(form));
        // existsByFormIdAndDeletedAtIsNull counts experiences in ANY status (DRAFT/ACTIVE/ARCHIVED).
        when(experienceRepository.existsByFormIdAndDeletedAtIsNull(formId)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class, () -> service.deleteForm(ownerId, formId));

        assertEquals(ErrorCode.FORM_ATTACHED_TO_ACTIVE_EXPERIENCE, ex.getErrorCode());
        assertNull(form.getDeletedAt(), "form must not be soft-deleted when blocked");
        verify(formRepository, never()).save(any());
    }

    @Test
    void deleteForm_notFoundWhenNotOwned() {
        when(formRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, formId))
                .thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> service.deleteForm(ownerId, formId));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(experienceRepository, never()).existsByFormIdAndDeletedAtIsNull(any());
    }
}
