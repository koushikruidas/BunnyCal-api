package io.bunnycal.admin.operations;

import io.bunnycal.admin.operations.dto.OperationsSummaryDto;
import io.bunnycal.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily operations landing page. OPERATIONS owns this surface; ADMIN and SUPER_ADMIN can
 * also view it. Summary only for now; deeper queue browsers land in later phases.
 */
@RestController
@RequestMapping("/api/admin/operations")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OPERATIONS')")
public class AdminOperationsController {

    private final OperationsService operationsService;

    public AdminOperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/summary")
    public ApiResponse<OperationsSummaryDto> summary() {
        return ApiResponse.success(operationsService.summary());
    }
}
