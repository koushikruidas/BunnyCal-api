package io.bunnycal.availability;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarEvent;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.CalendarRole;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.repository.CalendarEventRepository;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.calendar.service.CalendarConnectionManagementService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Availability is a property of a calendar, so the answer must not depend on who is doing the
 * booking.
 *
 * <p>It used to. A user could exclude a noisy calendar from their own event types — and then a
 * colleague would add them to a round-robin, and that calendar would start blocking their slots
 * again, through a mechanism they had never seen and could not reach. Same app, same calendar, two
 * different rules.
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
class GlobalAvailabilityIT {

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

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate DAY = LocalDate.of(2026, 5, 11);

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired CalendarConnectionCalendarRepository inventoryRepository;
    @Autowired CalendarEventRepository eventRepository;
    @Autowired CalendarBusyTimeService busyTimeService;
    @Autowired CalendarConnectionManagementService managementService;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, calendar_connections, calendar_connection_calendars, "
                + "calendar_events CASCADE");
    }

    @Test
    void aCalendarLeftOn_blocksTheUser() {
        User member = createUser("member@test.com");
        CalendarConnection conn = connection(member);
        calendar(conn, "work", true);
        busyEvent(member, conn, "work", "2026-05-11T10:00:00Z", "2026-05-11T11:00:00Z");

        assertThat(busyTimeService.busyIntervalsForDate(member.getId(), DAY, UTC)).hasSize(1);
    }

    /**
     * The trap, closed. Turning a calendar off is one answer, and it holds for every caller — the
     * user's own events and any team event they are a member of. There is no second code path for a
     * participant to be evaluated on.
     */
    @Test
    void aCalendarTurnedOff_stopsBlockingTheUserEverywhere() {
        User member = createUser("member@test.com");
        CalendarConnection conn = connection(member);
        CalendarConnectionCalendar noisy = calendar(conn, "family-calendar", true);
        busyEvent(member, conn, "family-calendar", "2026-05-11T10:00:00Z", "2026-05-11T11:00:00Z");

        assertThat(busyTimeService.busyIntervalsForDate(member.getId(), DAY, UTC))
                .as("on by default: if you connected it, it blocks you")
                .hasSize(1);

        managementService.setChecksAvailability(member.getId(), conn.getId(), "family-calendar", false);

        assertThat(busyTimeService.busyIntervalsForDate(member.getId(), DAY, UTC))
                .as("and it stays off no matter who is booking them — including in someone else's round-robin")
                .isEmpty();
        assertThat(busyTimeService.hasBusyConflict(
                member.getId(),
                Instant.parse("2026-05-11T10:00:00Z"),
                Instant.parse("2026-05-11T10:30:00Z"),
                UTC))
                .as("the confirm-time check must agree with the slot listing, or a booked slot is rejected")
                .isFalse();
    }

    @Test
    void otherCalendarsNeverBlockEvenWithALegacyFlag() {
        User member = createUser("member@test.com");
        CalendarConnection conn = connection(member);
        calendar(conn, "work", true);
        calendar(conn, "family-calendar", true, CalendarRole.OTHER);
        busyEvent(member, conn, "work", "2026-05-11T09:00:00Z", "2026-05-11T10:00:00Z");
        busyEvent(member, conn, "family-calendar", "2026-05-11T14:00:00Z", "2026-05-11T15:00:00Z");

        assertThat(busyTimeService.busyIntervalsForDate(member.getId(), DAY, UTC))
                .singleElement()
                .satisfies(interval ->
                        assertThat(interval.start().toInstant()).isEqualTo(Instant.parse("2026-05-11T09:00:00Z")));
    }

    /**
     * Rows ingested before per-calendar attribution existed carry no calendar id, so they cannot be
     * matched against the flag. They keep blocking — dropping them would silently free up time the
     * user is actually busy in.
     */
    @Test
    void legacyEventsWithNoCalendarAttribution_stillBlock() {
        User member = createUser("member@test.com");
        CalendarConnection conn = connection(member);
        calendar(conn, "work", false);
        busyEvent(member, conn, null, "2026-05-11T10:00:00Z", "2026-05-11T11:00:00Z");

        assertThat(busyTimeService.busyIntervalsForDate(member.getId(), DAY, UTC)).hasSize(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("Member")
                .username("u" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("UTC")
                .build());
    }

    private CalendarConnection connection(User owner) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(owner.getId());
        c.setProvider(CalendarProviderType.GOOGLE);
        c.setProviderUserId("sub-" + UUID.randomUUID());
        c.setRefreshTokenCiphertext("cipher");
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        c.setScopes(java.util.List.of("scope"));
        c.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        c.setDefaultWriteback(true);
        return connectionRepository.save(c);
    }

    private CalendarConnectionCalendar calendar(CalendarConnection conn, String externalId, boolean checks) {
        return calendar(conn, externalId, checks, CalendarRole.PRIMARY);
    }

    private CalendarConnectionCalendar calendar(CalendarConnection conn,
                                                  String externalId,
                                                  boolean checks,
                                                  CalendarRole role) {
        CalendarConnectionCalendar cal = new CalendarConnectionCalendar();
        cal.setConnectionId(conn.getId());
        cal.setExternalCalendarId(externalId);
        cal.setName(externalId);
        cal.setPrimary(role == CalendarRole.PRIMARY);
        cal.setCalendarRole(role);
        cal.setSelected(false);
        cal.setCanRead(true);
        cal.setCanWrite(true);
        cal.setChecksAvailability(checks);
        return inventoryRepository.save(cal);
    }

    private void busyEvent(User owner, CalendarConnection conn, String calendarId, String start, String end) {
        CalendarEvent e = new CalendarEvent();
        e.setUserId(owner.getId());
        e.setConnectionId(conn.getId());
        e.setProvider("GOOGLE");
        e.setExternalEventId(UUID.randomUUID().toString());
        e.setExternalCalendarId(calendarId);
        e.setStartsAt(Instant.parse(start));
        e.setEndsAt(Instant.parse(end));
        e.setCancelled(false);
        e.setDeleted(false);
        e.setBlocksAvailability(true);
        eventRepository.save(e);
    }
}
