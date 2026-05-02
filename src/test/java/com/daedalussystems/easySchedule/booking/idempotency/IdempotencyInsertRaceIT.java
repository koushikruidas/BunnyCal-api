package com.daedalussystems.easySchedule.booking.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// DB-level race-safety proof.
//
// The idempotency system's race guard is the SQL:
//   INSERT ... ON CONFLICT ON CONSTRAINT uq_idem_scope DO NOTHING
// Under N concurrent requests with the same (user_id, route, key) scope,
// exactly ONE INSERT must succeed (return rows-affected = 1); all others
// must silently do nothing (rows-affected = 0) — no errors, no duplicates.
//
// This test proves that guarantee holds against a real PostgreSQL instance.
// It is intentionally separate from the service-layer test so the DB
// contract and the application logic are verified independently.
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyInsertRaceIT {

    private static final int CONCURRENCY = 50;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE idempotency_keys (
                        id              UUID PRIMARY KEY,
                        key             VARCHAR(255) NOT NULL,
                        user_id         UUID NOT NULL,
                        route           VARCHAR(64)  NOT NULL,
                        request_hash    CHAR(64)     NOT NULL,
                        status          VARCHAR(16)  NOT NULL,
                        response_status INTEGER,
                        response_body   TEXT,
                        started_at      TIMESTAMPTZ  NOT NULL,
                        completed_at    TIMESTAMPTZ,
                        created_at      TIMESTAMPTZ  NOT NULL,
                        updated_at      TIMESTAMPTZ  NOT NULL,
                        CONSTRAINT uq_idem_scope UNIQUE (user_id, route, key)
                    )
                    """);
        }
    }

    // 50 concurrent threads each attempt to INSERT the same idempotency scope.
    // Exactly 1 must succeed (rows-affected = 1); the other 49 must do nothing
    // (rows-affected = 0) without raising any exception.
    @Test
    void concurrentInserts_sameScopeKey_exactlyOneWins() throws Exception {
        UUID userId = UUID.randomUUID();
        String route = "POST /api/bookings";
        String key = "test-key-" + UUID.randomUUID();
        String hash = "a".repeat(64);
        Instant now = Instant.now();

        String sql = """
                INSERT INTO idempotency_keys
                    (id, key, user_id, route, request_hash, status,
                     started_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_idem_scope DO NOTHING
                """;

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Callable<Integer>> tasks = new ArrayList<>(CONCURRENCY);
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> {
                Timestamp ts = Timestamp.from(now);
                try (Connection conn = open();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, key);
                    ps.setObject(3, userId);
                    ps.setString(4, route);
                    ps.setString(5, hash);
                    ps.setTimestamp(6, ts);
                    ps.setTimestamp(7, ts);
                    ps.setTimestamp(8, ts);
                    return ps.executeUpdate(); // 1 = inserted, 0 = conflicted
                }
            });
        }

        List<Future<Integer>> futures = pool.invokeAll(tasks, 10, TimeUnit.SECONDS);
        pool.shutdown();

        int wins = 0;
        int noops = 0;
        for (Future<Integer> f : futures) {
            int rowsAffected = f.get();
            if (rowsAffected == 1) wins++;
            else if (rowsAffected == 0) noops++;
        }

        assertEquals(1, wins,
                "exactly one insert must succeed under concurrent requests");
        assertEquals(CONCURRENCY - 1, noops,
                "all other concurrent requests must silently do nothing");

        // Verify the table contains exactly one row.
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND route = ? AND key = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, route);
            ps.setString(3, key);
            var rs = ps.executeQuery();
            rs.next();
            assertEquals(1, rs.getInt(1), "exactly one row must exist in the DB");
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
