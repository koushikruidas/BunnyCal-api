package com.daedalussystems.easySchedule.booking;

import com.daedalussystems.easySchedule.TestApplication;
import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApplication.class)
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false"
})
public abstract class AbstractBookingIT {

    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    static {
        // 🔥 FORCE START BEFORE SPRING
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        jdbc.execute(
                "TRUNCATE TABLE users, bookings, idempotency_keys, outbox_events, processed_events CASCADE"
        );
    }
    // ── Helpers ──────────────────────────────────────────────────────────────

    protected User createHost() {
        return userRepository.save(User.builder()
                .email("host-" + UUID.randomUUID() + "@test.com")
                .name("Test Host")
                .timezone("UTC")
                .build());
    }

    protected void insertIdempotencyKey(UUID id, String key, UUID userId,
            String route, String hash, String status, Instant at) {
        jdbc.update("""
                INSERT INTO idempotency_keys
                    (id, key, user_id, route, request_hash, status,
                     started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, key, userId, route, hash, status,
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }

    protected UUID insertPendingOutboxEvent(Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload,
                     status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'Booking', ?, 'BOOKING_CREATED', '{}',
                        'PENDING', 0, ?, ?, ?)
                """,
                id, UUID.randomUUID(),
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    protected UUID insertRetryingOutboxEvent(int attemptCount, Instant nextAttemptAt) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload,
                     status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'Booking', ?, 'BOOKING_CREATED', '{}',
                        'RETRYING', ?, ?, ?, ?)
                """,
                id, UUID.randomUUID(), attemptCount,
                Timestamp.from(nextAttemptAt), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    protected Map<String, Object> queryOutboxRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, attempt_count, next_attempt_at FROM outbox_events WHERE id = ?", id);
    }

    protected UUID insertProcessingOutboxEvent(int attemptCount, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload,
                     status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'Booking', ?, 'BOOKING_CREATED', '{}',
                        'PROCESSING', ?, ?, ?, ?)
                """,
                id, UUID.randomUUID(), attemptCount,
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    protected void inTx(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }

    protected UUID insertBooking(UUID hostId, UUID eventTypeId,
                                 Instant start, Instant end,
                                 String status, long version) {
        return insertBooking(hostId, eventTypeId, start, end, status, version, null);
    }

    protected UUID insertBooking(UUID hostId, UUID eventTypeId,
                                 Instant start, Instant end,
                                 String status, long version, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings
                    (id, host_id, event_type_id, start_time, end_time,
                     status, version, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                id, hostId, eventTypeId,
                Timestamp.from(start), Timestamp.from(end),
                status, version,
                expiresAt != null ? Timestamp.from(expiresAt) : null);
        return id;
    }

    protected Map<String, Object> queryBookingRow(UUID id) {
        return jdbc.queryForMap("SELECT status, version FROM bookings WHERE id = ?", id);
    }
}
