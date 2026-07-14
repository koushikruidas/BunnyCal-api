package io.bunnycal.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.UpdateEventTypeRequest;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeService;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.ownership.BookingOwnership;
import io.bunnycal.booking.ownership.BookingOwnershipRepository;
import io.bunnycal.booking.ownership.BookingOwnershipService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Duration;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The repair path that did not exist: an event type's booking calendar was frozen at creation and
 * could not be changed by any route, so a wrong or stale calendar could only be fixed by deleting
 * the event type.
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
class EventTypeUpdateIT {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired EventTypeRepository eventTypeRepository;
    @Autowired EventTypeService eventTypeService;
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired BookingOwnershipService ownershipService;
    @Autowired BookingOwnershipRepository ownershipRepository;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, event_types, event_type_participants, bookings, "
                + "calendar_connections, booking_ownership CASCADE");
    }

    @Test
    void update_repinsTheBookingCalendar() {
        User owner = createUser("owner@test.com");
        CalendarConnection first = googleConnection(owner.getId(), "first@gmail.com");
        CalendarConnection second = googleConnection(owner.getId(), "second@gmail.com");
        EventType et = persistEventType(owner.getId(), "demo", first, "first@gmail.com");

        eventTypeService.update(owner.getId(), et.getId(), updateProjection(second, "second@gmail.com"));

        EventType reloaded = eventTypeRepository.findById(et.getId()).orElseThrow();
        assertThat(reloaded.getProjectionConnectionId()).isEqualTo(second.getId());
        assertThat(reloaded.getProjectionCalendarId()).isEqualTo("second@gmail.com");
    }

    // A host who pinned the wrong calendar must be able to get back to "use my default", not merely
    // swap one pin for another. An explicitly-sent but empty destination unpins.
    @Test
    void update_withEmptyProjection_unpinsBackToTheDefault() {
        User owner = createUser("owner@test.com");
        CalendarConnection conn = googleConnection(owner.getId(), "first@gmail.com");
        EventType et = persistEventType(owner.getId(), "demo", conn, "first@gmail.com");

        eventTypeService.update(owner.getId(), et.getId(), new UpdateEventTypeRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null,
                new CreateEventTypeRequest.ProjectionDestinationRequest(null, null, null)));

        EventType reloaded = eventTypeRepository.findById(et.getId()).orElseThrow();
        assertThat(reloaded.getProjectionConnectionId()).isNull();
        assertThat(reloaded.getProjectionCalendarId()).isNull();
        assertThat(reloaded.getProjectionProvider()).isNull();
    }

    @Test
    void update_leavesUnsuppliedFieldsAlone() {
        User owner = createUser("owner@test.com");
        CalendarConnection conn = googleConnection(owner.getId(), "first@gmail.com");
        EventType et = persistEventType(owner.getId(), "demo", conn, "first@gmail.com");

        eventTypeService.update(owner.getId(), et.getId(), new UpdateEventTypeRequest(
                "Renamed", null, null, null, null, null, null, null, null, null, null, null, null));

        EventType reloaded = eventTypeRepository.findById(et.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Renamed");
        // The calendar was not mentioned, so it must survive untouched.
        assertThat(reloaded.getProjectionConnectionId()).isEqualTo(conn.getId());
        assertThat(reloaded.getProjectionCalendarId()).isEqualTo("first@gmail.com");
        assertThat(reloaded.getDuration()).isEqualTo(Duration.ofMinutes(30));
    }

    // THE regression this endpoint could otherwise have shipped. booking_ownership records where an
    // event was ACTUALLY written, and ensureOwnership runs on every outbox dispatch — not just at
    // creation. It used to re-derive ownership from the event type and throw if the two disagreed,
    // so editing a calendar would have broken cancel and reschedule on every booking that already
    // existed. An already-written booking must keep pointing at the calendar it lives on.
    @Test
    void update_doesNotDisturbOwnershipOfBookingsThatAlreadyExist() {
        User owner = createUser("owner@test.com");
        CalendarConnection first = googleConnection(owner.getId(), "first@gmail.com");
        CalendarConnection second = googleConnection(owner.getId(), "second@gmail.com");
        EventType et = persistEventType(owner.getId(), "demo", first, "first@gmail.com");

        // A booking exists and has been written to the ORIGINAL calendar.
        Booking booking = persistBooking(owner.getId(), et.getId());
        BookingOwnership created = ownershipService.ensureOwnership(booking, et);
        assertThat(created.getProjectionCalendarId()).isEqualTo("first@gmail.com");

        // The host re-points the event type at a different calendar.
        eventTypeService.update(owner.getId(), et.getId(), updateProjection(second, "second@gmail.com"));
        EventType repointed = eventTypeRepository.findById(et.getId()).orElseThrow();

        // Every later lifecycle event (cancel, reschedule) re-enters ensureOwnership. It must not
        // throw, and it must still resolve to the calendar the event was actually written to.
        BookingOwnership after = ownershipService.ensureOwnership(booking, repointed);
        assertThat(after.getProjectionConnectionId()).isEqualTo(first.getId());
        assertThat(after.getProjectionCalendarId()).isEqualTo("first@gmail.com");
        assertThat(ownershipRepository.findByBookingId(booking.getId()).orElseThrow()
                .getProjectionCalendarId()).isEqualTo("first@gmail.com");
    }

    @Test
    void update_rejectsAnEventTypeOwnedBySomeoneElse() {
        User owner = createUser("owner@test.com");
        User stranger = createUser("stranger@test.com");
        CalendarConnection conn = googleConnection(owner.getId(), "first@gmail.com");
        EventType et = persistEventType(owner.getId(), "demo", conn, "first@gmail.com");

        assertThatThrownBy(() -> eventTypeService.update(stranger.getId(), et.getId(),
                new UpdateEventTypeRequest("Hijacked", null, null, null, null, null, null, null,
                        null, null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UpdateEventTypeRequest updateProjection(CalendarConnection connection, String calendarId) {
        return new UpdateEventTypeRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null,
                new CreateEventTypeRequest.ProjectionDestinationRequest(
                        "google", connection.getId().toString(), calendarId));
    }

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email).name("U " + email).username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("UTC").build());
    }

    private CalendarConnection googleConnection(UUID userId, String accountEmail) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(userId);
        c.setProvider(CalendarProviderType.GOOGLE);
        c.setProviderUserId("sub-" + UUID.randomUUID());
        c.setAccountEmail(accountEmail);
        c.setRefreshTokenCiphertext("cipher");
        c.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        c.setScopes(List.of("https://www.googleapis.com/auth/calendar"));
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        return connectionRepository.save(c);
    }

    private EventType persistEventType(UUID ownerId, String slug,
                                       CalendarConnection projection, String calendarId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(ownerId)
                .name("Demo Call")
                .slug(slug)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .conferencingProvider(io.bunnycal.common.enums.ConferencingProviderType.NONE)
                .projectionProvider(CalendarProviderType.GOOGLE)
                .projectionConnectionId(projection.getId())
                .projectionCalendarId(calendarId)
                .build());
    }

    private Booking persistBooking(UUID hostId, UUID eventTypeId) {
        UUID id = UUID.randomUUID();
        Instant start = Instant.parse("2026-09-01T10:00:00Z");
        jdbc.update("INSERT INTO bookings (id, host_id, event_type_id, start_time, end_time, status, "
                        + "guest_email, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', 'guest@test.com', 0, NOW(), NOW())",
                id, hostId, eventTypeId,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(1800)));
        return Booking.builder().id(id).hostId(hostId).eventTypeId(eventTypeId)
                .startTime(start).endTime(start.plusSeconds(1800)).build();
    }
}
