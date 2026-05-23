package com.daedalussystems.easySchedule.integration.controller;

import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.integration.ProviderCatalogResponse;
import com.daedalussystems.easySchedule.integration.ProviderCatalogService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integrations/providers")
public class ProviderCatalogController {
    private final ProviderCatalogService providerCatalogService;

    public ProviderCatalogController(ProviderCatalogService providerCatalogService) {
        this.providerCatalogService = providerCatalogService;
    }

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<ProviderCatalogResponse>> catalog(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(providerCatalogService.catalogForUser(userId)));
    }

    private static UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        if (principal instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ex) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
