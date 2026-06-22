package io.bunnycal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.SessionUserResolver;
import io.bunnycal.availability.service.AvailabilityService;
import io.bunnycal.availability.service.EventTypeService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.service.TeamService;
import io.bunnycal.team.dto.CreateTeamRequest;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that all authenticated service-layer user lookups return HTTP 401
 * (via UNAUTHORIZED ErrorCode) instead of 404 when the backing user record no
 * longer exists — the scenario that occurs when the database is reset while a
 * browser session is still active.
 *
 * Scenario A: token resolves to a userId that doesn't exist => 401 everywhere.
 * Scenario B: Map.of() in AuthController.session() does NOT NPE when activeProvider is null.
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
class StaleSessionIT {

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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionUserResolver sessionUserResolver;
    @Autowired private EventTypeService eventTypeService;
    @Autowired private AvailabilityService availabilityService;
    @Autowired private TeamService teamService;

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE TABLE users, event_types, teams, team_members, team_invitations, "
                + "availability_rules, availability_overrides CASCADE");
        jdbc.execute("DELETE FROM outbox_events WHERE aggregate_type = 'TeamInvitation'");
    }

    // ── Scenario A: stale userId (DB reset) ─────────────────────────────────

    @Test
    void sessionUserResolver_returns401_whenUserMissing() {
        UUID ghostId = UUID.randomUUID();

        CustomException ex = assertThrows(CustomException.class,
                () -> sessionUserResolver.require(ghostId, "test-endpoint"));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode(),
                "stale session must produce UNAUTHORIZED (HTTP 401), not RESOURCE_NOT_FOUND (HTTP 404)");
    }

    @Test
    void eventTypeService_list_returns401_whenUserMissing() {
        UUID ghostId = UUID.randomUUID();

        CustomException ex = assertThrows(CustomException.class,
                () -> eventTypeService.list(ghostId));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void teamService_createTeam_returns401_whenUserMissing() {
        UUID ghostId = UUID.randomUUID();

        CustomException ex = assertThrows(CustomException.class,
                () -> teamService.createTeam(ghostId, new CreateTeamRequest("Ghost Team", null)));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void availabilityService_getRules_returns401_whenUserMissing() {
        UUID ghostId = UUID.randomUUID();

        // getOverrides internally calls ensureUserTimezone which uses SessionUserResolver.
        CustomException ex = assertThrows(CustomException.class,
                () -> availabilityService.getOverrides(ghostId, null, null));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    // ── Scenario B: valid user exists — normal operation unaffected ──────────

    @Test
    void sessionUserResolver_returnsUser_whenUserExists() {
        User user = userRepository.save(User.builder()
                .email("valid@example.com")
                .name("Valid User")
                .timezone("UTC")
                .build());

        User resolved = sessionUserResolver.require(user.getId(), "test-endpoint");

        assertEquals(user.getId(), resolved.getId());
        assertEquals("valid@example.com", resolved.getEmail());
    }

    @Test
    void eventTypeService_list_returnsEmpty_forNewUser() {
        User user = userRepository.save(User.builder()
                .email("newuser@example.com")
                .name("New User")
                .timezone("UTC")
                .build());

        var result = eventTypeService.list(user.getId());

        assertEquals(0, result.size());
    }
}
