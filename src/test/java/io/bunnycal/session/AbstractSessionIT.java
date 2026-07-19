package io.bunnycal.session;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
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
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false"
})
public abstract class AbstractSessionIT {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
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
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected EventTypeRepository eventTypeRepository;
    @Autowired protected PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        jdbc.execute(
                "TRUNCATE TABLE users, event_types, event_sessions, session_registrations, "
                        + "booking_action_tokens, outbox_events, processed_events, "
                        + "bookings, booking_assignments CASCADE");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    protected User createHost() {
        return userRepository.save(User.builder()
                .email("host-" + UUID.randomUUID() + "@test.com")
                .name("Test Host")
                .timezone("UTC")
                .build());
    }

    protected EventType createGroupEventType(UUID hostId, int capacity) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name("Group Workshop")
                .slug("group-workshop-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.GROUP)
                .capacity(capacity)
                .build());
    }

    protected Instant nextHour() {
        return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
    }

    protected Map<String, Object> querySession(UUID sessionId) {
        return jdbc.queryForMap(
                "SELECT status, confirmed_count, capacity, version, calendar_sequence FROM event_sessions WHERE id = ?",
                sessionId);
    }

    protected Map<String, Object> queryRegistration(UUID registrationId) {
        return jdbc.queryForMap(
                "SELECT status, version FROM session_registrations WHERE id = ?",
                registrationId);
    }

    protected int countRegistrationsByStatus(UUID sessionId, String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_registrations WHERE session_id = ? AND status = ?",
                Integer.class, sessionId, status);
        return count == null ? 0 : count;
    }

    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    protected void inTx(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }
}
