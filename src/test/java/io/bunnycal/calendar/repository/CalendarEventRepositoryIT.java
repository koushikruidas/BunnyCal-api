package io.bunnycal.calendar.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.calendar.domain.CalendarEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Persistence contract tests for availability policy version 1. These pin the JPQL form of the
 * side-effect-free domain policy so a shortcut query cannot silently diverge from slot listing,
 * confirmation, the Availability UI, round-robin, or collective scheduling.
 */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false"
})
class CalendarEventRepositoryIT {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Redis is autowired in some beans; point it at a fake but never start it —
        // the repository test never touches the cache path.
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "16379");
    }

    @Autowired private CalendarEventRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private UUID connA;
    private UUID connB;
    private UUID userId;
    private final Instant windowStart = Instant.parse("2026-05-10T00:00:00Z");
    private final Instant windowEnd   = Instant.parse("2026-05-11T00:00:00Z");

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE calendar_events, calendar_connections CASCADE");
        userId = UUID.randomUUID();
        connA = UUID.randomUUID();
        connB = UUID.randomUUID();
        insertConnection(connA, userId, "GOOGLE");
        insertConnection(connB, userId, "MICROSOFT");
    }

    @Test
    void availabilityPolicyQuery_includesOnlyEnabledReadableVisiblePrimaryCalendar() {
        insertInventory(connA, "work", true);
        insertInventory(connB, "personal", false);
        UUID included = insertEvent(connA, "work", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        UUID excluded = insertEvent(connB, "personal", "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");

        List<CalendarEvent> result = repository.findBusyOnAvailabilityCalendars(userId, windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(included);
        assertThat(result).extracting(CalendarEvent::getId).doesNotContain(excluded);
    }

    @Test
    void availabilityPolicyQuery_legacyNullIsScopedToItsConnectionsSelection() {
        insertInventory(connA, "work", true);
        insertInventory(connB, "personal", false);
        UUID included = insertEvent(connA, null, "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        UUID excluded = insertEvent(connB, null, "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");

        List<CalendarEvent> result = repository.findBusyOnAvailabilityCalendars(userId, windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(included);
        assertThat(result).extracting(CalendarEvent::getId).doesNotContain(excluded);
    }

    @Test
    void availabilityPolicyQuery_disconnectedConnectionNeverContributes() {
        insertInventory(connA, "work", true);
        UUID event = insertEvent(connA, "work", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        jdbc.update("UPDATE calendar_connections SET status = 'REVOKED' WHERE id = ?", connA);

        List<CalendarEvent> result = repository.findBusyOnAvailabilityCalendars(userId, windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).doesNotContain(event);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void insertConnection(UUID id, UUID userId, String provider) {
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id,
                     refresh_token_ciphertext, last_token_expires_at, scopes, status,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ct', NOW(), '{}', 'ACTIVE', NOW(), NOW())
                """,
                id, userId, provider, "user-" + provider.toLowerCase());
    }

    private UUID insertEvent(UUID connectionId, String externalCalendarId, String startsAt, String endsAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_events
                    (id, user_id, connection_id, provider, external_event_id,
                     starts_at, ends_at, cancelled, external_calendar_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'GOOGLE', ?, ?, ?, FALSE, ?, NOW(), NOW())
                """,
                id, userId, connectionId, "ext-" + id,
                Timestamp.from(Instant.parse(startsAt)),
                Timestamp.from(Instant.parse(endsAt)),
                externalCalendarId);
        return id;
    }

    private void insertInventory(UUID connectionId, String externalCalendarId, boolean checksAvailability) {
        jdbc.update("""
                INSERT INTO calendar_connection_calendars
                    (id, connection_id, external_calendar_id, name, is_primary, calendar_role,
                     is_selected, checks_availability, can_read, can_write, hidden,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 'PRIMARY', FALSE, ?, TRUE, TRUE, FALSE, NOW(), NOW())
                """, UUID.randomUUID(), connectionId, externalCalendarId, externalCalendarId,
                checksAvailability);
    }

}
