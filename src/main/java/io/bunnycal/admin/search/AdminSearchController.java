package io.bunnycal.admin.search;

import io.bunnycal.admin.search.dto.AdminSearchDtos.AdminSearchResponse;
import io.bunnycal.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/search")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'SUPPORT', 'FINANCE', 'OPERATIONS')")
public class AdminSearchController {

    private final AdminSearchService service;

    public AdminSearchController(AdminSearchService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AdminSearchResponse> search(@RequestParam("q") String query) {
        return ApiResponse.success(service.search(query));
    }
}
