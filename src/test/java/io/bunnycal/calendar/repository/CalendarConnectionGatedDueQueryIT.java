package io.bunnycal.calendar.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.calendar.domain.CalendarConnection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JPQL-level test for {@link CalendarConnectionRepository#findDueForSyncBatchGated} against a
 * real Postgres instance. The scheduler unit test proves the flag selects this query; this
 * test proves the webhook-fresh gating SQL itself returns exactly the right rows:
 * <ul>
 *   <li>watchless / expired-channel ACTIVE rows are always due;</li>
 *   <li>fresh-channel ACTIVE rows are due only once {@code lastSyncedAt} is past the backstop;</li>
 *   <li>fresh-channel + recently-synced ACTIVE rows are excluded (poll skipped this tick);</li>
 *   <li>backed-off FAILED/ERROR rows follow the unchanged {@code nextRetryAt} rule.</li>
 * </ul>
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
class CalendarConnectionGatedDueQueryIT {

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
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "16379");
    }

    @Autowired private CalendarConnectionRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private final Instant now = Instant.parse("2026-05-10T12:00:00Z");
    // backstop = 15 min → threshold is 12:00 minus 15 min = 11:45.
    private final Instant backstopThreshold = now.minusSeconds(15 * 60);

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE calendar_connections CASCADE");
    }

    @Test
    void freshChannelRecentlySynced_isExcluded() {
        // Channel valid for another hour; synced 5 min ago (after the backstop threshold).
        UUID id = insertConnection("ACTIVE",
                /*channelExpires*/ now.plusSeconds(3600),
                /*lastSynced*/     now.minusSeconds(300),
                /*nextRetry*/      null);

        List<CalendarConnection> due = query();

        assertThat(due).extracting(CalendarConnection::getId).doesNotContain(id);
    }

    @Test
    void freshChannelButBackstopElapsed_isDue() {
        // Channel still valid, but last sync was 30 min ago — past the 15 min backstop.
        UUID id = insertConnection("ACTIVE",
                now.plusSeconds(3600),
                now.minusSeconds(30 * 60),
                null);

        assertThat(query()).extracting(CalendarConnection::getId).contains(id);
    }

    @Test
    void watchlessActive_isAlwaysDue() {
        // No webhook channel at all → every tick, regardless of how recently synced.
        UUID id = insertConnection("ACTIVE",
                /*channelExpires*/ null,
                /*lastSynced*/     now.minusSeconds(1), // just synced
                null);

        assertThat(query()).extracting(CalendarConnection::getId).contains(id);
    }

    @Test
    void expiredChannelActive_isAlwaysDue() {
        // Channel expired an hour ago → back to every-tick even if just synced.
        UUID id = insertConnection("ACTIVE",
                now.minusSeconds(3600),
                now.minusSeconds(1),
                null);

        assertThat(query()).extracting(CalendarConnection::getId).contains(id);
    }

    @Test
    void backedOffFailed_followsNextRetryRule_evenWithFreshChannel() {
        // FAILED with next_retry in the future is NOT due; with next_retry elapsed it IS due.
        UUID notYet = insertConnection("FAILED",
                now.plusSeconds(3600), now.minusSeconds(1),
                /*nextRetry*/ now.plusSeconds(600));
        UUID ready = insertConnection("ERROR",
                now.plusSeconds(3600), now.minusSeconds(1),
                /*nextRetry*/ now.minusSeconds(60));

        List<CalendarConnection> due = query();

        assertThat(due).extracting(CalendarConnection::getId).contains(ready);
        assertThat(due).extracting(CalendarConnection::getId).doesNotContain(notYet);
    }

    @Test
    void revoked_isNeverDue() {
        UUID id = insertConnection("REVOKED", null, now.minusSeconds(9999), null);
        assertThat(query()).extracting(CalendarConnection::getId).doesNotContain(id);
    }

    private List<CalendarConnection> query() {
        return repository.findDueForSyncBatchGated(now, backstopThreshold, PageRequest.of(0, 100));
    }

    private UUID insertConnection(String status, Instant channelExpires, Instant lastSynced, Instant nextRetry) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id,
                     refresh_token_ciphertext, last_token_expires_at, scopes, status,
                     webhook_channel_expires_at, last_synced_at, next_retry_at,
                     created_at, updated_at)
                VALUES (?, ?, 'GOOGLE', ?, 'ct', NOW(), '{}', ?, ?, ?, ?, NOW(), NOW())
                """,
                id, UUID.randomUUID(), "user-" + id, status,
                channelExpires == null ? null : Timestamp.from(channelExpires),
                lastSynced == null ? null : Timestamp.from(lastSynced),
                nextRetry == null ? null : Timestamp.from(nextRetry));
        return id;
    }
}
