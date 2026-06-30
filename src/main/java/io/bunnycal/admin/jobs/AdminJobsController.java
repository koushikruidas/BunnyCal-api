package io.bunnycal.admin.jobs;

import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.jobs.dto.AdminOutboxEventDto;
import io.bunnycal.admin.jobs.dto.AdminSyncDeadLetterDto;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * System Jobs browser over outbox events and sync dead letters. OPERATIONS owns this surface.
 * There is currently no separate email queue table, so this controller exposes only the
 * persisted queues that exist today.
 */
@RestController
@RequestMapping("/api/admin/jobs")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OPERATIONS')")
public class AdminJobsController {

    private final SystemJobsService service;

    public AdminJobsController(SystemJobsService service) {
        this.service = service;
    }

    @GetMapping("/outbox")
    public ApiResponse<PageResponse<AdminOutboxEventDto>> outbox(
            @RequestParam(value = "status", required = false) OutboxEventStatus status,
            @RequestParam(value = "aggregateType", required = false) String aggregateType,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ApiResponse.success(service.searchOutbox(status, aggregateType, eventType, page, size));
    }

    @GetMapping("/dead-letters")
    public ApiResponse<List<AdminSyncDeadLetterDto>> deadLetters(
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResponse.success(service.deadLetters(provider, limit));
    }

    @PostMapping("/outbox/{id}/retry")
    public ApiResponse<AdminOutboxEventDto> retryOutbox(
            Authentication auth, @PathVariable UUID id, @RequestBody ReasonRequest request) {
        return ApiResponse.success(service.retryOutbox(adminId(auth), id, request.reason()));
    }

    @PostMapping("/dead-letters/{id}/requeue")
    public ApiResponse<AdminSyncDeadLetterDto> requeueDeadLetter(
            Authentication auth, @PathVariable UUID id, @RequestBody ReasonRequest request) {
        return ApiResponse.success(service.requeueDeadLetter(adminId(auth), id, request.reason()));
    }

    public record ReasonRequest(String reason) {
    }

    private static UUID adminId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }
}
