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
 * JPQL-level test for {@link CalendarEventRepository#findBusySelected} against a real
 * Postgres instance. The unit test for {@link io.bunnycal.calendar.service.CalendarBusyTimeService}
 * stubs the repository and proves the partition is composed correctly; this test
 * proves the JPQL itself filters rows the way the documented selection model says
 * it should. Together they pin down the whole "calendar selection drives busy
 * intervals" invariant.
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
    void calendarScoped_onlyMatchingCalendarIdContributes() {
        // connB has TWO Microsoft calendars; user selected only the work one.
        UUID workEvent   = insertEvent(connB, "AQMkAD-work",   "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        UUID familyEvent = insertEvent(connB, "AQMkAD-family", "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");

        List<CalendarEvent> result = repository.findBusySelected(
                List.of(impossibleUuid()),                // wholeConnectionIds — none
                List.of(connB),                            // calendarScopedConnectionIds
                List.of("AQMkAD-work"),                    // selectedExternalCalendarIds
                windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(workEvent);
        assertThat(result).extracting(CalendarEvent::getId).doesNotContain(familyEvent);
    }

    @Test
    void calendarScoped_legacyNullExternalCalendarIdRow_isKeptByWildcard() {
        // Legacy row with NULL external_calendar_id (pre-multi-calendar ingestion).
        // The wildcard rule says it should still contribute when its connection is
        // in the calendar-scoped bucket. This locks Decision 1 in the SQL layer.
        UUID legacyEvent = insertEvent(connB, null, "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");

        List<CalendarEvent> result = repository.findBusySelected(
                List.of(impossibleUuid()),
                List.of(connB),
                List.of("AQMkAD-work"),
                windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(legacyEvent);
    }

    @Test
    void wholeConnection_allEventsOnConnectionContributeRegardlessOfCalendarId() {
        UUID workEvent   = insertEvent(connB, "AQMkAD-work",   "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        UUID familyEvent = insertEvent(connB, "AQMkAD-family", "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");
        UUID legacyNull  = insertEvent(connB, null,             "2026-05-10T13:00:00Z", "2026-05-10T14:00:00Z");

        List<CalendarEvent> result = repository.findBusySelected(
                List.of(connB),                            // whole connection
                List.of(impossibleUuid()),                 // no calendar-scoped
                List.of("__unused-sentinel__"),
                windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId)
                .containsExactlyInAnyOrder(workEvent, familyEvent, legacyNull);
    }

    @Test
    void noCrossLeakBetweenConnections() {
        // Identical calendar id strings on two different connections must not bleed
        // into each other (they wouldn't in reality — Google/Microsoft ids are
        // globally unique per provider — but the query must still gate by connection).
        UUID aEvent = insertEvent(connA, "shared-id", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        UUID bEvent = insertEvent(connB, "shared-id", "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");

        List<CalendarEvent> result = repository.findBusySelected(
                List.of(impossibleUuid()),
                List.of(connA),                            // only connA in scoped bucket
                List.of("shared-id"),
                windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(aEvent);
        assertThat(result).extracting(CalendarEvent::getId).doesNotContain(bEvent);
    }

    @Test
    void cancelledRows_excluded() {
        UUID liveEvent = insertEvent(connB, "AQMkAD-work", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        UUID cancelledEvent = insertEvent(connB, "AQMkAD-work", "2026-05-10T11:00:00Z", "2026-05-10T12:00:00Z");
        jdbc.update("UPDATE calendar_events SET cancelled = TRUE WHERE id = ?", cancelledEvent);

        List<CalendarEvent> result = repository.findBusySelected(
                List.of(impossibleUuid()),
                List.of(connB),
                List.of("AQMkAD-work"),
                windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(liveEvent);
    }

    @Test
    void timeWindowFilter_excludesEventsOutsideWindow() {
        insertEvent(connB, "AQMkAD-work", "2026-05-09T22:00:00Z", "2026-05-09T23:00:00Z"); // before
        UUID inside = insertEvent(connB, "AQMkAD-work", "2026-05-10T09:00:00Z", "2026-05-10T10:00:00Z");
        insertEvent(connB, "AQMkAD-work", "2026-05-12T09:00:00Z", "2026-05-12T10:00:00Z"); // after

        List<CalendarEvent> result = repository.findBusySelected(
                List.of(impossibleUuid()),
                List.of(connB),
                List.of("AQMkAD-work"),
                windowEnd, windowStart);

        assertThat(result).extracting(CalendarEvent::getId).containsExactly(inside);
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

    private static UUID impossibleUuid() {
        return new UUID(0L, 0L);
    }
}
