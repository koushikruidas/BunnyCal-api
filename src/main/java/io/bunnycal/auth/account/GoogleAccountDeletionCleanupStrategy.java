package io.bunnycal.auth.account;

import io.bunnycal.calendar.service.CalendarOAuthService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoogleAccountDeletionCleanupStrategy implements AccountDeletionProviderCleanupStrategy {

    private final CalendarOAuthService calendarOAuthService;

    @Override
    public String providerKey() {
        return "google";
    }

    @Override
    public void cleanup(UUID userId) {
        calendarOAuthService.disconnectGoogle(userId);
    }
}
