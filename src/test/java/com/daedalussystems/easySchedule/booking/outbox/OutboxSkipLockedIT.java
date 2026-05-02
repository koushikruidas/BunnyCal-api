package com.daedalussystems.easySchedule.booking.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

// DB-level proof that SELECT … FOR UPDATE SKIP LOCKED gives disjoint claims
// across concurrent workers.
//
// The claim contract:
//   Given N events in PENDING and W concurrent workers each claiming a
//   batch, no two workers must claim the same event_id. The union of all
//   claims must equal the full set of inserted events.
//
// This test verifies that guarantee at the raw JDBC level, independent of
// the Spring/JPA stack. If this test is green, OutboxWorker.claimBatch()
// cannot produce duplicate processing under concurrent polls.
@Testcontainers(disabledWithoutDocker = true)
class OutboxSkipLockedIT {

    private static final int EVENT_COUNT  = 20;
    private static final int WORKER_COUNT = 4;
    private static final int BATCH_SIZE   = EVENT_COUNT / WORKER_COUNT; // 5

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE outbox_events (
                        id              UUID         PRIMARY KEY,
                        status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                        next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
                    )
                    """);
        }
    }

    // 4 concurrent workers each claim a batch of 5 from a pool of 20 PENDING
    // events using SELECT … FOR UPDATE SKIP LOCKED.
    //
    // Invariants verified:
    //   1. No event_id appears in more than one worker's claim set.
    //   2. The union of all claims contains every inserted event_id.
    @Test
    void concurrentWorkers_claimDisjointBatches_noOverlap() throws Exception {
        List<UUID> eventIds = insertPendingEvents(EVENT_COUNT);

        ExecutorService pool = Executors.newFixedThreadPool(WORKER_COUNT);
        List<Callable<Set<UUID>>> workers = new ArrayList<>(WORKER_COUNT);
        for (int i = 0; i < WORKER_COUNT; i++) {
            workers.add(() -> claimBatch(BATCH_SIZE));
        }

        List<Future<Set<UUID>>> futures = pool.invokeAll(workers, 15, TimeUnit.SECONDS);
        pool.shutdown();

        // Collect all claimed IDs. Check for duplicates.
        Set<UUID> all = new HashSet<>();
        int totalClaimed = 0;
        for (Future<Set<UUID>> f : futures) {
            Set<UUID> batch = f.get();
            totalClaimed += batch.size();
            for (UUID id : batch) {
                assertTrue(all.add(id),
                        "event " + id + " was claimed by more than one worker");
            }
        }

        assertEquals(EVENT_COUNT, totalClaimed,
                "total claimed events must equal the number of inserted events");
        assertEquals(new HashSet<>(eventIds), all,
                "the union of all claims must equal the full event set");
    }

    // Each simulated worker runs in its own JDBC connection (= its own
    // session). It opens a TX, SELECTs with SKIP LOCKED, UPDATEs to
    // PROCESSING, then commits. The returned set is the IDs it claimed.
    private static Set<UUID> claimBatch(int batchSize) throws Exception {
        Set<UUID> claimed = new HashSet<>();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);

            // Step 1: claim with SKIP LOCKED.
            String selectSql = """
                    SELECT id FROM outbox_events
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= NOW()
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """;
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setInt(1, batchSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add((UUID) rs.getObject(1));
                    }
                }
            }

            if (!ids.isEmpty()) {
                // Step 2: mark PROCESSING so the lock can be released.
                // Build a parameterised IN list.
                String placeholders = "?,".repeat(ids.size());
                placeholders = placeholders.substring(0, placeholders.length() - 1);
                String updateSql =
                        "UPDATE outbox_events SET status = 'PROCESSING' WHERE id IN (" + placeholders + ")";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setObject(i + 1, ids.get(i));
                    }
                    ps.executeUpdate();
                }
                claimed.addAll(ids);
            }

            conn.commit();
        }
        return claimed;
    }

    private static List<UUID> insertPendingEvents(int count) throws Exception {
        List<UUID> ids = new ArrayList<>(count);
        String sql = "INSERT INTO outbox_events (id, status, next_attempt_at, created_at) VALUES (?,?,?,?)";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (int i = 0; i < count; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                ps.setObject(1, id);
                ps.setString(2, "PENDING");
                ps.setTimestamp(3, now);
                ps.setTimestamp(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return ids;
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
