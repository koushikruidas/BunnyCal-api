package io.bunnycal.admin.announcements.dto;

import io.bunnycal.announcements.Announcement;
import io.bunnycal.announcements.AnnouncementAudience;
import io.bunnycal.announcements.AnnouncementLevel;
import java.time.Instant;
import java.util.UUID;

public final class AdminAnnouncementDtos {

    private AdminAnnouncementDtos() {
    }

    public record AdminAnnouncementDto(
            UUID id,
            String title,
            String body,
            AnnouncementLevel level,
            AnnouncementAudience audience,
            Instant startsAt,
            Instant endsAt,
            boolean active,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static AdminAnnouncementDto from(Announcement announcement) {
            return new AdminAnnouncementDto(
                    announcement.getId(),
                    announcement.getTitle(),
                    announcement.getBody(),
                    announcement.getLevel(),
                    announcement.getAudience(),
                    announcement.getStartsAt(),
                    announcement.getEndsAt(),
                    announcement.isActive(),
                    announcement.getCreatedBy(),
                    announcement.getCreatedAt(),
                    announcement.getUpdatedAt());
        }
    }

    public record CreateAnnouncementRequest(
            String title,
            String body,
            AnnouncementLevel level,
            AnnouncementAudience audience,
            Instant startsAt,
            Instant endsAt,
            Boolean active,
            String reason) {
    }

    public record UpdateAnnouncementRequest(
            String title,
            String body,
            AnnouncementLevel level,
            AnnouncementAudience audience,
            Instant startsAt,
            Instant endsAt,
            Boolean active,
            String reason) {
    }

    public record SetAnnouncementActiveRequest(Boolean active, String reason) {
    }

    public record DeleteAnnouncementRequest(String reason) {
    }
}
