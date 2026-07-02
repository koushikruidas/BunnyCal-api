package io.bunnycal.auth.account;

import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ZoomAccountDeletionCleanupStrategy implements AccountDeletionProviderCleanupStrategy {

    private final ZoomConferencingOAuthService zoomConferencingOAuthService;

    @Override
    public String providerKey() {
        return "zoom";
    }

    @Override
    public void cleanup(UUID userId) {
        zoomConferencingOAuthService.disconnect(userId);
    }
}
