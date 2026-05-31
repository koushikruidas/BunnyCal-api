package io.bunnycal.calendar.client;

public record ProviderCalendarInventoryEntry(
        String externalCalendarId,
        String name,
        boolean primary,
        boolean canRead,
        boolean canWrite,
        boolean hidden
) {}
