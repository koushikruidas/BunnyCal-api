package io.bunnycal.booking.draft.controller;

import io.bunnycal.booking.draft.dto.DraftCreateRequest;
import io.bunnycal.booking.draft.dto.DraftResponse;
import io.bunnycal.booking.draft.dto.DraftUpdateRequest;
import io.bunnycal.booking.draft.service.DraftOrganizerService;
import io.bunnycal.common.api.ApiResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/drafts")
public class PublicDraftController {
    private final DraftOrganizerService draftOrganizerService;

    public PublicDraftController(DraftOrganizerService draftOrganizerService) {
        this.draftOrganizerService = draftOrganizerService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody DraftCreateRequest request) {
        DraftOrganizerService.DraftCreated created = draftOrganizerService.create(request);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "draft", created.draft(),
                "managementToken", created.managementToken()
        )));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<DraftResponse>> get(
            @PathVariable String slug,
            @RequestHeader("X-Draft-Token") String token) {
        return ResponseEntity.ok(ApiResponse.success(draftOrganizerService.getForManage(slug, token)));
    }

    @PutMapping("/{slug}")
    public ResponseEntity<ApiResponse<DraftResponse>> update(
            @PathVariable String slug,
            @RequestHeader("X-Draft-Token") String token,
            @RequestBody DraftUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(draftOrganizerService.update(slug, token, request)));
    }
}
