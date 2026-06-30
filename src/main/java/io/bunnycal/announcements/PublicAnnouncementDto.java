package io.bunnycal.announcements;

import java.time.Instant;
import java.util.UUID;

public record PublicAnnouncementDto(
        UUID id,
        String title,
        String body,
        AnnouncementLevel level,
        AnnouncementAudience audience,
        Instant startsAt,
        Instant endsAt) {

    public static PublicAnnouncementDto from(Announcement announcement) {
        return new PublicAnnouncementDto(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getBody(),
                announcement.getLevel(),
                announcement.getAudience(),
                announcement.getStartsAt(),
                announcement.getEndsAt());
    }
}
