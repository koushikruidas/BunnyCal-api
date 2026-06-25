package io.bunnycal.auth.account;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountDeletionJobRepository extends JpaRepository<AccountDeletionJob, UUID> {

    Optional<AccountDeletionJob> findByUserId(UUID userId);

    boolean existsByUserIdAndStatusIn(UUID userId, Collection<AccountDeletionJobStatus> statuses);

    List<AccountDeletionJob> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            Collection<AccountDeletionJobStatus> statuses,
            Instant nextAttemptAt,
            Pageable pageable);
}
