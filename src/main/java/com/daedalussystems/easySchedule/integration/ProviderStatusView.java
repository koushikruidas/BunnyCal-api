package com.daedalussystems.easySchedule.integration;

public record ProviderStatusView(
        String connectionStatus,
        boolean isConnected,
        boolean isActionRequired
) {
}
