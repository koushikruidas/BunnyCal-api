package com.daedalussystems.easySchedule.booking.constraint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Validates that the bookings_no_overlap EXCLUDE USING gist constraint is
// the authoritative enforcer of Invariant #1. No Spring context is needed —
// the constraint is a DB-level guarantee, tested here as a pure JDBC test.
//
// When this test is green you can delete the application-level overlap
// pre-check in BookingService (findByHostIdAndStartTime... + its caller).
// disabledWithoutDocker = true: test is SKIPPED (not failed) on machines
// where the Docker daemon is not reachable. Runs automatically in any
// environment that has Docker (Docker Desktop, CI with Docker service, etc.).
@Testcontainers(disabledWithoutDocker = true)
class BookingOverlapConstraintIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSERT = """
            INSERT INTO bookings (id, host_id, start_time, end_time, status)
            VALUES (?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, ?)
            """;

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
            // PostgreSQL does NOT support EXCLUDE on a partitioned parent table.
            // The EXCLUDE lives on each child. This is correct: hash(host_id)
            // guarantees all bookings for one host land in one partition, so
            // a per-partition EXCLUDE enforces the global non-overlap invariant.
            stmt.execute("""
                    CREATE TABLE bookings (
                        id         UUID         NOT NULL,
                        host_id    UUID         NOT NULL,
                        start_time TIMESTAMPTZ  NOT NULL,
                        end_time   TIMESTAMPTZ  NOT NULL,
                        status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                        PRIMARY KEY (id, host_id)
                    ) PARTITION BY HASH (host_id)
                    """);
            // 4 partitions suffice for the test; production migration uses 16.
            // Each EXCLUDE creates a backing GiST index; PG requires unique
            // index names per schema, so suffix with partition number.
            // BookingService prefix-checks "bookings_no_overlap", which still
            // matches "bookings_no_overlap_p0X" for every partition.
            for (int i = 0; i < 4; i++) {
                stmt.execute("CREATE TABLE bookings_p0" + i
                        + " PARTITION OF bookings FOR VALUES WITH (MODULUS 4, REMAINDER " + i + ")");
                stmt.execute("ALTER TABLE bookings_p0" + i
                        + " ADD CONSTRAINT bookings_no_overlap_p0" + i
                        + " EXCLUDE USING gist ("
                        + "   host_id WITH =,"
                        + "   tstzrange(start_time, end_time) WITH &&"
                        + " ) WHERE (status IN ('PENDING','CONFIRMED'))");
            }
        }
    }

    // Core acceptance criterion: two PENDING bookings with overlapping time
    // ranges for the same host must raise SQLState 23P01 with the exact
    // constraint name — so BookingService.isOverlapExclusionViolation() can
    // identify it unambiguously.
    @Test
    void overlappingActiveBookings_raise23P01_withCorrectConstraintName() throws Exception {
        UUID hostId = UUID.randomUUID();
        try (Connection conn = openConnection()) {
            insert(conn, hostId, "2026-06-01T10:00:00Z", "2026-06-01T10:30:00Z", "PENDING");

            PreparedStatement ps = conn.prepareStatement(INSERT);
            bind(ps, UUID.randomUUID(), hostId, "2026-06-01T10:15:00Z", "2026-06-01T10:45:00Z", "PENDING");

            SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                    "overlapping PENDING booking must be rejected");
            assertEquals("23P01", ex.getSQLState(),
                    "expected exclusion_violation SQLState");
            assertTrue(ex.getMessage().contains("bookings_no_overlap"),
                    "expected constraint name in error: " + ex.getMessage());
        }
    }

    // Adjacent bookings (end of A == start of B) must not overlap.
    // tstzrange uses [start, end) semantics by default — touching edges
    // are disjoint, so both inserts succeed.
    @Test
    void adjacentBookings_samHost_succeed() throws Exception {
        UUID hostId = UUID.randomUUID();
        try (Connection conn = openConnection()) {
            assertDoesNotThrow(() ->
                    insert(conn, hostId, "2026-06-01T09:00:00Z", "2026-06-01T09:30:00Z", "PENDING"));
            assertDoesNotThrow(() ->
                    insert(conn, hostId, "2026-06-01T09:30:00Z", "2026-06-01T10:00:00Z", "PENDING"));
        }
    }

    // Partial constraint: a CANCELLED booking in a slot must not block a
    // subsequent PENDING booking in the same slot. Terminal statuses are
    // excluded from the WHERE clause, so they are invisible to the constraint.
    @Test
    void cancelledThenPending_samSlot_succeed() throws Exception {
        UUID hostId = UUID.randomUUID();
        try (Connection conn = openConnection()) {
            assertDoesNotThrow(() ->
                    insert(conn, hostId, "2026-06-01T11:00:00Z", "2026-06-01T11:30:00Z", "CANCELLED"));
            assertDoesNotThrow(() ->
                    insert(conn, hostId, "2026-06-01T11:00:00Z", "2026-06-01T11:30:00Z", "PENDING"),
                    "PENDING after CANCELLED in same slot must be allowed");
        }
    }

    // Overlap between two different hosts must be allowed — the constraint
    // is scoped to host_id WITH =.
    @Test
    void overlappingBookings_differentHosts_succeed() throws Exception {
        try (Connection conn = openConnection()) {
            assertDoesNotThrow(() ->
                    insert(conn, UUID.randomUUID(), "2026-06-01T12:00:00Z", "2026-06-01T12:30:00Z", "PENDING"));
            assertDoesNotThrow(() ->
                    insert(conn, UUID.randomUUID(), "2026-06-01T12:00:00Z", "2026-06-01T12:30:00Z", "PENDING"),
                    "same time slot for different hosts must be allowed");
        }
    }

    private static void insert(Connection conn, UUID hostId, String start, String end, String status)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
            bind(ps, UUID.randomUUID(), hostId, start, end, status);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, UUID id, UUID hostId,
            String start, String end, String status) throws SQLException {
        ps.setString(1, id.toString());
        ps.setString(2, hostId.toString());
        ps.setString(3, start);
        ps.setString(4, end);
        ps.setString(5, status);
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
