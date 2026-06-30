package io.bunnycal.announcements;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID>, JpaSpecificationExecutor<Announcement> {

    @Query("""
            select a
            from Announcement a
            where a.active = true
              and (a.startsAt is null or a.startsAt <= :now)
              and (a.endsAt is null or a.endsAt >= :now)
              and a.audience in :audiences
            """)
    List<Announcement> findActiveForAudiences(
            @Param("now") Instant now,
            @Param("audiences") Collection<AnnouncementAudience> audiences);
}
