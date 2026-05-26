package io.bunnycal.integration;

public record ProviderStatusView(
        String connectionStatus,
        boolean isConnected,
        boolean isActionRequired
) {
}
