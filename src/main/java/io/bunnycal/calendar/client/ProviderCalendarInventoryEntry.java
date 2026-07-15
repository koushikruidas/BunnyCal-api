package io.bunnycal.calendar.client;

public record ProviderCalendarInventoryEntry(
        String externalCalendarId,
        String name,
        boolean primary,
        boolean canRead,
        boolean canWrite,
        boolean hidden,
        /** Graph reports this per calendar via allowedOnlineMeetingProviders. */
        boolean supportsNativeTeams
) {
    public ProviderCalendarInventoryEntry(String externalCalendarId,
                                          String name,
                                          boolean primary,
                                          boolean canRead,
                                          boolean canWrite,
                                          boolean hidden) {
        this(externalCalendarId, name, primary, canRead, canWrite, hidden, false);
    }
}
