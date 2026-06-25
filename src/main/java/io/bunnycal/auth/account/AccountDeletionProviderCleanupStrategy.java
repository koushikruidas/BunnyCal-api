package io.bunnycal.auth.account;

import java.util.UUID;

public interface AccountDeletionProviderCleanupStrategy {

    String providerKey();

    void cleanup(UUID userId);
}
