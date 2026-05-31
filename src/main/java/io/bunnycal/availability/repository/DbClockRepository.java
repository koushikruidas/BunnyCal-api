package io.bunnycal.availability.repository;

import io.bunnycal.common.time.TimeSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Repository;

@Repository
public class DbClockRepository implements TimeSource {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Instant now() {
        Object result = entityManager.createNativeQuery("SELECT now()").getSingleResult();

        if (result instanceof Instant instant) {
            return instant;
        }
        if (result instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (result instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (result instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneOffset.UTC).toInstant();
        }

        throw new IllegalStateException(
                "Unexpected DB clock value type: " + (result == null ? "null" : result.getClass().getName()));
    }
}
