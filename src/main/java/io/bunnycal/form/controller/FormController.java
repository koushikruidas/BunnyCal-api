package io.bunnycal.form.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.form.dto.BulkQuestionRequest;
import io.bunnycal.form.dto.FormRequest;
import io.bunnycal.form.dto.FormResponse;
import io.bunnycal.form.dto.QuestionRequest;
import io.bunnycal.form.dto.QuestionResponse;
import io.bunnycal.form.dto.ReorderQuestionsRequest;
import io.bunnycal.form.service.FormService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/forms")
public class FormController {

    private final FormService formService;

    public FormController(FormService formService) {
        this.formService = formService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FormResponse>> create(Authentication auth,
                                                            @RequestBody FormRequest request) {
        return ResponseEntity.ok(ApiResponse.success(formService.createForm(userId(auth), request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FormResponse>>> list(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(formService.getForms(userId(auth))));
    }

    @GetMapping("/{formId}")
    public ResponseEntity<ApiResponse<FormResponse>> get(Authentication auth,
                                                         @PathVariable UUID formId) {
        return ResponseEntity.ok(ApiResponse.success(formService.getForm(userId(auth), formId)));
    }

    @DeleteMapping("/{formId}")
    public ResponseEntity<ApiResponse<Void>> delete(Authentication auth,
                                                    @PathVariable UUID formId) {
        formService.deleteForm(userId(auth), formId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{formId}/questions")
    public ResponseEntity<ApiResponse<QuestionResponse>> addQuestion(Authentication auth,
                                                                     @PathVariable UUID formId,
                                                                     @RequestBody QuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(formService.addQuestion(userId(auth), formId, request)));
    }

    @PostMapping("/{formId}/questions/bulk")
    public ResponseEntity<ApiResponse<List<QuestionResponse>>> addQuestions(Authentication auth,
                                                                            @PathVariable UUID formId,
                                                                            @RequestBody BulkQuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                formService.addQuestions(userId(auth), formId, request.questions())));
    }

    @PutMapping("/{formId}/questions/{questionId}")
    public ResponseEntity<ApiResponse<QuestionResponse>> updateQuestion(Authentication auth,
                                                                        @PathVariable UUID formId,
                                                                        @PathVariable UUID questionId,
                                                                        @RequestBody QuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                formService.updateQuestion(userId(auth), formId, questionId, request)));
    }

    @DeleteMapping("/{formId}/questions/{questionId}")
    public ResponseEntity<ApiResponse<Void>> deleteQuestion(Authentication auth,
                                                            @PathVariable UUID formId,
                                                            @PathVariable UUID questionId) {
        formService.deleteQuestion(userId(auth), formId, questionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{formId}/questions/reorder")
    public ResponseEntity<ApiResponse<Void>> reorder(Authentication auth,
                                                     @PathVariable UUID formId,
                                                     @RequestBody ReorderQuestionsRequest request) {
        formService.reorderQuestions(userId(auth), formId, request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private UUID userId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        if (principal instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
