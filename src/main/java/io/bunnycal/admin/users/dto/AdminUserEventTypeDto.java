package io.bunnycal.admin.users.dto;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ConferencingProviderType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One of a user's booking pages, as an admin sees it. Durations are surfaced in minutes
 * rather than as ISO-8601 strings because every support question about them ("is the
 * buffer wrong?") is asked in minutes.
 *
 * <p>Soft-deleted event types are included and flagged via {@code deletedAt}: "the page
 * they deleted last week" is a question admins actually need to answer.
 *
 * <p>No created/updated timestamps: the {@code event_types} table carries them but the
 * entity does not map them, and adding that mapping is a schema concern beyond this view.
 */
public record AdminUserEventTypeDto(
        UUID id,
        String name,
        String slug,
        EventKind kind,
        long durationMinutes,
        long bufferBeforeMinutes,
        long bufferAfterMinutes,
        long slotIntervalMinutes,
        boolean published,
        String location,
        ConferencingProviderType conferencingProvider,
        int capacity,
        Instant deletedAt) {

    public static AdminUserEventTypeDto from(EventType e) {
        return new AdminUserEventTypeDto(
                e.getId(),
                e.getName(),
                e.getSlug(),
                e.getKind(),
                minutes(e.getDuration()),
                minutes(e.getBufferBefore()),
                minutes(e.getBufferAfter()),
                minutes(e.getSlotInterval()),
                e.isPublished(),
                e.getLocation(),
                e.getConferencingProvider(),
                e.getCapacity(),
                e.getDeletedAt());
    }

    private static long minutes(Duration d) {
        return d == null ? 0L : d.toMinutes();
    }
}
