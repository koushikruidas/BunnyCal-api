package io.bunnycal.booking.draft.repository;

import io.bunnycal.booking.draft.domain.DraftLifecycleState;
import io.bunnycal.booking.draft.domain.HostDraft;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HostDraftRepository extends JpaRepository<HostDraft, UUID> {
    Optional<HostDraft> findByPublicSlugAndExpiresAtAfter(String publicSlug, Instant now);
    Optional<HostDraft> findByPublicSlug(String publicSlug);
    Optional<HostDraft> findByPublicSlugAndState(String publicSlug, DraftLifecycleState state);
    Optional<HostDraft> findByPublicSlugAndManagementTokenHash(String publicSlug, String managementTokenHash);
    Optional<HostDraft> findFirstByShadowUserIdOrderByCreatedAtDesc(UUID shadowUserId);
    List<HostDraft> findTop200ByStateAndExpiresAtBeforeOrderByExpiresAtAsc(DraftLifecycleState state, Instant now);
}
