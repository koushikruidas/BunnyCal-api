package com.daedalussystems.easySchedule.booking;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
//import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Shared base for all booking integration tests.
//
// Each subclass must provide:
//   • @Container PostgreSQLContainer (postgres:16-alpine)
//   • @Container GenericContainer    (redis:7-alpine)
//   • static @DynamicPropertySource  pointing Spring at those containers
//
// This class owns everything else:
//   • ddl-auto=none — we build the full schema in @BeforeAll; no Hibernate DDL.
//   • @BeforeAll: creates every table from scratch, matching production structure.
//     bookings is PARTITION BY HASH (4 children, EXCLUDE on each child), exactly
//     as the V3_0__bookings.sql Flyway migration requires.
//   • @BeforeEach: purges all rows so every test starts from an empty DB.
//   • Shared helpers: createHost, insertIdempotencyKey, insertPendingOutboxEvent,
//     insertProcessingOutboxEvent, inTx.
//
// @TestInstance(PER_CLASS): one instance per test class, so @BeforeAll can be
// non-static and receive @Autowired beans.
@SpringBootTest
//@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
    "spring.security.oauth2.client.registration.google.client-id=test-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
    // none: we control 100 % of the schema; Hibernate touches nothing.
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.docker.compose.enabled=false",
    // Push OTLP step far into the future; no collector runs in tests.
    "management.otlp.metrics.export.step=PT99999S",
    "management.otlp.metrics.export.connect-timeout=100ms"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractBookingIT {

    @Autowired protected JdbcTemplate             jdbc;
    @Autowired protected UserRepository           userRepository;
    @Autowired protected PlatformTransactionManager txManager;

    // Builds the full schema before any test in the class runs.
    //
    // Design notes:
    //   • pgcrypto: required for gen_random_uuid() used in DEFAULT clauses.
    //     In PostgreSQL 13+ it is a built-in, but declaring the extension
    //     is explicit and harmless on any supported version.
    //   • btree_gist: required for the UUID "WITH =" operator inside a GiST
    //     index (the EXCLUDE constraint on bookings_p0X).
    //   • users is created first so idempotency_keys can reference it via FK.
    //   • bookings uses 4 partitions instead of production's 16; semantics are
    //     identical and the reduced count speeds up schema creation.
    //   • Constraint names follow the production convention ("bookings_no_overlap_p0X")
    //     so BookingService.isOverlapExclusionViolation()'s prefix-match on
    //     "bookings_no_overlap" continues to work without modification.
    //   • cleanAllTables() is called first to remove residual data from a
    //     previous test class when the Spring context is reused (cached).
    @BeforeAll
    void buildSchema() {
        cleanAllTables();

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");

        // ── users ────────────────────────────────────────────────────────────
        jdbc.execute("DROP TABLE IF EXISTS users CASCADE");
        jdbc.execute("""
                CREATE TABLE users (
                    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                    email      VARCHAR(255) NOT NULL UNIQUE,
                    name       VARCHAR(120) NOT NULL,
                    timezone   VARCHAR(50)  NOT NULL,
                    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
                    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
                )
                """);
        jdbc.execute("CREATE INDEX idx_users_email ON users (email)");

        // ── idempotency_keys ──────────────────────────────────────────────────
        jdbc.execute("DROP TABLE IF EXISTS idempotency_keys CASCADE");
        jdbc.execute("""
                CREATE TABLE idempotency_keys (
                    id              UUID         PRIMARY KEY,
                    key             VARCHAR(255) NOT NULL,
                    user_id         UUID         NOT NULL,
                    route           VARCHAR(64)  NOT NULL,
                    request_hash    CHAR(64)     NOT NULL,
                    status          VARCHAR(16)  NOT NULL,
                    response_status INTEGER,
                    response_body   TEXT,
                    started_at      TIMESTAMPTZ  NOT NULL,
                    completed_at    TIMESTAMPTZ,
                    created_at      TIMESTAMPTZ  NOT NULL,
                    updated_at      TIMESTAMPTZ  NOT NULL,
                    CONSTRAINT uq_idem_scope UNIQUE (user_id, route, key),
                    CONSTRAINT fk_idem_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        jdbc.execute("CREATE INDEX idx_idem_status_updated_at ON idempotency_keys (status, updated_at)");
        jdbc.execute("CREATE INDEX idx_idem_created_at        ON idempotency_keys (created_at)");
        jdbc.execute("CREATE INDEX idx_idem_request_hash      ON idempotency_keys (request_hash)");

        // ── outbox_events ─────────────────────────────────────────────────────
        jdbc.execute("DROP TABLE IF EXISTS outbox_events CASCADE");
        jdbc.execute("""
                CREATE TABLE outbox_events (
                    id              UUID         PRIMARY KEY,
                    aggregate_type  VARCHAR(64)  NOT NULL,
                    aggregate_id    UUID         NOT NULL,
                    event_type      VARCHAR(128) NOT NULL,
                    payload         TEXT         NOT NULL,
                    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                    attempt_count   INT          NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    last_error      TEXT,
                    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT outbox_events_status_check
                        CHECK (status IN ('PENDING','PROCESSING','PROCESSED','FAILED'))
                )
                """);
        jdbc.execute("""
                CREATE INDEX idx_outbox_pending
                    ON outbox_events (next_attempt_at, created_at)
                    WHERE status = 'PENDING'
                """);
        jdbc.execute("""
                CREATE INDEX idx_outbox_processing
                    ON outbox_events (updated_at)
                    WHERE status = 'PROCESSING'
                """);

        // ── processed_events ──────────────────────────────────────────────────
        jdbc.execute("DROP TABLE IF EXISTS processed_events CASCADE");
        jdbc.execute("""
                CREATE TABLE processed_events (
                    event_id     UUID        PRIMARY KEY,
                    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);

        // ── bookings (partitioned) ────────────────────────────────────────────
        jdbc.execute("DROP TABLE IF EXISTS bookings CASCADE");
        jdbc.execute("""
                CREATE TABLE bookings (
                    id            UUID        NOT NULL,
                    host_id       UUID        NOT NULL,
                    event_type_id UUID        NOT NULL,
                    start_time    TIMESTAMPTZ NOT NULL,
                    end_time      TIMESTAMPTZ NOT NULL,
                    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    CONSTRAINT bookings_pkey PRIMARY KEY (id, host_id),
                    CONSTRAINT bookings_status_check
                        CHECK (status IN ('PENDING','CONFIRMED','CANCELLED',
                                          'EXPIRED','COMPLETED','REJECTED')),
                    CONSTRAINT bookings_time_order_check
                        CHECK (start_time < end_time)
                ) PARTITION BY HASH (host_id)
                """);

        for (int i = 0; i < 4; i++) {
            jdbc.execute("CREATE TABLE bookings_p0" + i
                    + " PARTITION OF bookings"
                    + " FOR VALUES WITH (MODULUS 4, REMAINDER " + i + ")");
            jdbc.execute("ALTER TABLE bookings_p0" + i
                    + " ADD CONSTRAINT bookings_no_overlap_p0" + i
                    + " EXCLUDE USING gist ("
                    + "   host_id                         WITH =,"
                    + "   tstzrange(start_time, end_time) WITH &&"
                    + " ) WHERE (status IN ('PENDING','CONFIRMED'))");
        }

        // Indexes matching production (V3_0__bookings.sql).
        jdbc.execute("CREATE INDEX idx_bookings_host_start ON bookings (host_id, start_time)");
        jdbc.execute("""
                CREATE INDEX idx_bookings_status_updated
                    ON bookings (status, updated_at)
                    WHERE status IN ('PENDING','CONFIRMED')
                """);
    }

    @BeforeEach
    void cleanAllData() {
        cleanAllTables();
    }

    private void cleanAllTables() {
        jdbc.execute("DELETE FROM processed_events");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("DELETE FROM idempotency_keys");
        jdbc.execute("DELETE FROM bookings");
        jdbc.execute("DELETE FROM users");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Creates and persists a User that BookingService can lock with SELECT FOR UPDATE. */
    protected User createHost() {
        return userRepository.save(User.builder()
                .email("host-" + UUID.randomUUID() + "@test.com")
                .name("Test Host")
                .timezone("UTC")
                .build());
    }

    /**
     * Inserts an idempotency key row directly, bypassing IdempotencyService.
     * Used to prime IN_PROGRESS / COMPLETED / FAILED states for reaper and
     * polling tests.
     *
     * Parameter order matches the SQL column list exactly — verified against
     * the uq_idem_scope constraint definition to prevent silent test corruption.
     */
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

    /** Inserts a PENDING outbox event, ready for a worker to claim. */
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

    /**
     * Inserts a PROCESSING outbox event at the given time.
     * Simulates a worker that claimed the event but crashed before committing.
     */
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

    /**
     * Runs work inside a new transaction.
     * Required when calling @Modifying repository methods directly from tests
     * (outside a service's @Transactional boundary).
     */
    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    protected void inTx(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }
}
