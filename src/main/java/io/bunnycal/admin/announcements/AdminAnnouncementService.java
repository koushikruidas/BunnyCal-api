package io.bunnycal.admin.announcements;

import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.AdminAnnouncementDto;
import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.CreateAnnouncementRequest;
import io.bunnycal.admin.announcements.dto.AdminAnnouncementDtos.UpdateAnnouncementRequest;
import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.announcements.Announcement;
import io.bunnycal.announcements.AnnouncementAudience;
import io.bunnycal.announcements.AnnouncementLevel;
import io.bunnycal.announcements.AnnouncementRepository;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAnnouncementService {

    private static final String TARGET_TYPE = "ANNOUNCEMENT";
    private static final int MAX_SIZE = 100;

    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;

    public AdminAnnouncementService(AnnouncementRepository announcementRepository,
                                    UserRepository userRepository,
                                    AdminAuditService auditService) {
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAnnouncementDto> search(String query,
                                                     Boolean active,
                                                     AnnouncementLevel level,
                                                     AnnouncementAudience audience,
                                                     int page,
                                                     int size) {
        Specification<Announcement> spec = (root, ignored, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.get("title"), "")), pattern),
                        cb.like(cb.lower(root.get("body")), pattern)));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (level != null) {
                predicates.add(cb.equal(root.get("level"), level));
            }
            if (audience != null) {
                predicates.add(cb.equal(root.get("audience"), audience));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return PageResponse.of(
                announcementRepository.findAll(spec, page(page, size)),
                AdminAnnouncementDto::from);
    }

    @Transactional
    public AdminAnnouncementDto create(UUID adminId, CreateAnnouncementRequest request) {
        requireReason(request.reason());
        validate(request.title(), request.body(), request.startsAt(), request.endsAt());

        Announcement announcement = Announcement.builder()
                .title(normalizeTitle(request.title()))
                .body(request.body().trim())
                .level(request.level() == null ? AnnouncementLevel.INFO : request.level())
                .audience(request.audience() == null ? AnnouncementAudience.ALL : request.audience())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .active(request.active() == null || request.active())
                .createdBy(adminId)
                .build();

        Announcement saved = announcementRepository.save(announcement);
        AdminAnnouncementDto after = AdminAnnouncementDto.from(saved);
        audit(adminId, "ANNOUNCEMENT_CREATE", saved.getId(), request.reason(), null, after);
        return after;
    }

    @Transactional
    public AdminAnnouncementDto update(UUID adminId, UUID announcementId, UpdateAnnouncementRequest request) {
        requireReason(request.reason());
        validate(request.title(), request.body(), request.startsAt(), request.endsAt());

        Announcement announcement = requireAnnouncement(announcementId);
        AdminAnnouncementDto before = AdminAnnouncementDto.from(announcement);
        announcement.setTitle(normalizeTitle(request.title()));
        announcement.setBody(request.body().trim());
        announcement.setLevel(request.level() == null ? AnnouncementLevel.INFO : request.level());
        announcement.setAudience(request.audience() == null ? AnnouncementAudience.ALL : request.audience());
        announcement.setStartsAt(request.startsAt());
        announcement.setEndsAt(request.endsAt());
        if (request.active() != null) {
            announcement.setActive(request.active());
        }
        Announcement saved = announcementRepository.save(announcement);
        AdminAnnouncementDto after = AdminAnnouncementDto.from(saved);
        audit(adminId, "ANNOUNCEMENT_UPDATE", saved.getId(), request.reason(), before, after);
        return after;
    }

    @Transactional
    public AdminAnnouncementDto setActive(UUID adminId, UUID announcementId, boolean active, String reason) {
        requireReason(reason);

        Announcement announcement = requireAnnouncement(announcementId);
        AdminAnnouncementDto before = AdminAnnouncementDto.from(announcement);
        announcement.setActive(active);
        Announcement saved = announcementRepository.save(announcement);
        AdminAnnouncementDto after = AdminAnnouncementDto.from(saved);
        audit(adminId, active ? "ANNOUNCEMENT_ACTIVATE" : "ANNOUNCEMENT_DEACTIVATE", saved.getId(), reason, before, after);
        return after;
    }

    @Transactional
    public void delete(UUID adminId, UUID announcementId, String reason) {
        requireReason(reason);

        Announcement announcement = requireAnnouncement(announcementId);
        AdminAnnouncementDto before = AdminAnnouncementDto.from(announcement);
        announcementRepository.delete(announcement);
        audit(adminId, "ANNOUNCEMENT_DELETE", announcementId, reason, before, null);
    }

    private Announcement requireAnnouncement(UUID announcementId) {
        return announcementRepository.findById(announcementId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Announcement not found."));
    }

    private void audit(UUID adminId, String action, UUID targetId, String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(User::getEmail).orElse(null);
        auditService.record(adminId, email, action, TARGET_TYPE, targetId, reason, before, after);
    }

    private static void validate(String title, String body, Instant startsAt, Instant endsAt) {
        if (body == null || body.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "body is required.");
        }
        if (title != null && title.trim().length() > 160) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "title must be 160 characters or fewer.");
        }
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "endsAt must be after startsAt.");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "reason is required.");
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.trim();
    }

    private static PageRequest page(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
