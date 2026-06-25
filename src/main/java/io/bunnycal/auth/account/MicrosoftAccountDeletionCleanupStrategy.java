package io.bunnycal.auth.account;

import io.bunnycal.calendar.service.MicrosoftCalendarOAuthService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MicrosoftAccountDeletionCleanupStrategy implements AccountDeletionProviderCleanupStrategy {

    private final MicrosoftCalendarOAuthService microsoftCalendarOAuthService;

    @Override
    public String providerKey() {
        return "microsoft";
    }

    @Override
    public void cleanup(UUID userId) {
        microsoftCalendarOAuthService.disconnectMicrosoft(userId);
    }
}
