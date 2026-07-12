package io.bunnycal.auth.repository;

import io.bunnycal.auth.domain.user.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    interface TimezoneCountRow {
        String getTimezone();
        Long getUserCount();
    }

    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
    Optional<User> findByIdAndDeletionRequestedAtIsNull(UUID id);

    /** Admin user search by partial, case-insensitive email. */
    java.util.List<User> findTop20ByEmailContainingIgnoreCaseOrderByEmailAsc(String email);

    /** Count of users in a given status — admin dashboard metrics. */
    long countByStatus(io.bunnycal.common.enums.UserStatus status);

    /** New users created since {@code since} — admin growth metric. */
    long countByCreatedAtGreaterThanEqual(Instant since);

    /** New users created in an arbitrary analytics window. */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    @Query("""
            select u.timezone as timezone, count(u) as userCount
            from User u
            where u.timezone is not null and u.timezone <> ''
            group by u.timezone
            order by count(u) desc, u.timezone asc
            """)
    List<TimezoneCountRow> timezoneCounts();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByEmail(String email);
}
