package io.bunnycal.announcements;

import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.billing.entitlement.PlanTier;
import io.bunnycal.common.time.TimeSource;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementQueryService {

    private final AnnouncementRepository announcementRepository;
    private final EntitlementService entitlementService;
    private final UserRepository userRepository;
    private final TimeSource timeSource;

    public AnnouncementQueryService(AnnouncementRepository announcementRepository,
                                    EntitlementService entitlementService,
                                    UserRepository userRepository,
                                    TimeSource timeSource) {
        this.announcementRepository = announcementRepository;
        this.entitlementService = entitlementService;
        this.userRepository = userRepository;
        this.timeSource = timeSource;
    }

    @Transactional
    public List<PublicAnnouncementDto> active(Authentication authentication) {
        Instant now = timeSource.now();
        Set<AnnouncementAudience> audiences = resolveAudiences(authentication);
        return announcementRepository.findActiveForAudiences(now, audiences).stream()
                .sorted(publicOrder())
                .map(PublicAnnouncementDto::from)
                .toList();
    }

    private Set<AnnouncementAudience> resolveAudiences(Authentication authentication) {
        UUID userId = authenticatedUserId(authentication);
        if (userId == null) {
            return Set.of(AnnouncementAudience.ALL);
        }
        PlanTier tier = entitlementService.resolve(userId).tier();
        return tier == PlanTier.FREE
                ? Set.of(AnnouncementAudience.ALL, AnnouncementAudience.FREE)
                : Set.of(AnnouncementAudience.ALL, AnnouncementAudience.PAID);
    }

    private UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        try {
            UUID userId = UUID.fromString(authentication.getPrincipal().toString());
            return userRepository.existsById(userId) ? userId : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Comparator<Announcement> publicOrder() {
        return Comparator
                .comparingInt((Announcement a) -> switch (a.getLevel()) {
                    case CRITICAL -> 0;
                    case WARNING -> 1;
                    case INFO -> 2;
                })
                .thenComparing(Announcement::getStartsAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Announcement::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }
}
