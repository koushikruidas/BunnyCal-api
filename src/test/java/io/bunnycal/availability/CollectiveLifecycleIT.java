package io.bunnycal.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.EventTypeParticipantResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeLifecycleOutboxPayload;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.availability.service.EventTypeService;
import io.bunnycal.availability.service.ParticipantReadinessStatus;
import io.bunnycal.availability.service.PublishReadinessService;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicHoldResponse;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.service.CollectiveSlotTokenService;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.TestApplication;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
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
 * Phase 6: Integration tests for Collective event type lifecycle.
 *
 * <p>Covers:
 * <ul>
 *   <li>Publish / unpublish / republish state transitions</li>
 *   <li>Slot and hold enforcement when unpublished</li>
 *   <li>Readiness evaluation: READY, NO_CALENDAR, DEGRADED_CALENDAR, INACTIVE, REVOKED</li>
 *   <li>Auto-unpublish: calendar revoked, participant removed, participant deactivated</li>
 *   <li>Degraded state: transient FAILED connection stays published</li>
 *   <li>Historical integrity: participant removed after confirmed bookings exist</li>
 *   <li>New COLLECTIVE event starts unpublished; other kinds start published</li>
 *   <li>Regression: ONE_ON_ONE / ROUND_ROBIN / GROUP bookings unaffected</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false"
})
class CollectiveLifecycleIT {

    private static final Instant SLOT_START = Instant.parse("2026-06-15T09:00:00Z");
    private static final Instant SLOT_END   = Instant.parse("2026-06-15T09:30:00Z");
    private static final Duration HOLD_DUR  = Duration.ofMinutes(15);

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
    @Autowired EventTypeParticipantService participantService;
    @Autowired PublishReadinessService publishReadinessService;
    @Autowired PublicBookingService publicBookingService;
    @Autowired CollectiveSlotTokenService collectiveSlotTokenService;
    @Autowired BookingAssignmentRepository assignmentRepository;

    @BeforeEach
    void clean() {
        jdbc.execute("""
                TRUNCATE TABLE users, event_types, event_type_participants, availability_rules,
                    bookings, booking_assignments, booking_action_tokens,
                    collective_participant_holds, idempotency_keys, outbox_events, processed_events,
                    calendar_connections, calendar_connection_calendars,
                    calendar_events, calendar_event_mappings CASCADE
                """);
    }

    // ── 1. Publish / unpublish / republish ────────────────────────────────────

    @Test
    void newCollectiveEvent_startsUnpublished() {
        Fixture f = buildReadyFixture("new");
        assertThat(f.eventType.isPublished()).isFalse();
    }

    @Test
    void newOneOnOneEvent_startsPublished() {
        User owner = createUser("owner-1on1@test.com", "owner1on1");
        EventType et = eventTypeRepository.save(EventType.builder()
                .userId(owner.getId()).name("1on1").slug("one-on-one-t")
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30)).holdDuration(HOLD_DUR)
                .kind(EventKind.ONE_ON_ONE).capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE).build());
        assertThat(et.isPublished()).isTrue();
    }

    @Test
    void publish_whenAllReady_setsPublishedTrue() {
        Fixture f = buildReadyFixture("pub");
        assertThat(f.eventType.isPublished()).isFalse();

        PublishReadinessResponse response = eventTypeService.publish(f.owner.getId(), f.eventType.getId());

        assertThat(response.published()).isTrue();
        assertThat(response.publishable()).isTrue();
        assertThat(response.degraded()).isFalse();
        assertThat(response.reasons()).isEmpty();
        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isTrue();
    }

    @Test
    void unpublish_setsPublishedFalse() {
        Fixture f = buildReadyFixture("unpub");
        publishAll(f);

        PublishReadinessResponse response = eventTypeService.unpublish(f.owner.getId(), f.eventType.getId());

        assertThat(response.published()).isFalse();
        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
    }

    @Test
    void republish_afterUnpublish_succeeds() {
        Fixture f = buildReadyFixture("repub");
        publishAll(f);
        eventTypeService.unpublish(f.owner.getId(), f.eventType.getId());

        PublishReadinessResponse response = eventTypeService.publish(f.owner.getId(), f.eventType.getId());

        assertThat(response.published()).isTrue();
        // Two publish events in total: initial publish + republish after manual unpublish.
        assertThat(outboxEventCount(f.eventType.getId(), EventTypeLifecycleOutboxPayload.EVENT_PUBLISHED)).isEqualTo(2);
    }

    @Test
    void publish_whenParticipantNotReady_throws() {
        Fixture f = buildFixtureNoCalendar("noready");

        assertThatThrownBy(() -> eventTypeService.publish(f.owner.getId(), f.eventType.getId()))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNPUBLISHABLE_EVENT_TYPE));
    }

    // ── 2. Slot and hold enforcement ──────────────────────────────────────────

    @Test
    void hold_whenUnpublished_throwsEventTypeNotPublished() {
        Fixture f = buildReadyFixture("gate");
        // COLLECTIVE event type is unpublished by default.
        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));

        assertThatThrownBy(() -> publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "guest@test.com", "Guest", token)))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.EVENT_TYPE_NOT_PUBLISHED));
    }

    @Test
    void hold_whenPublished_succeeds() {
        Fixture f = buildReadyFixture("gate2");
        publishAll(f);

        String token = collectiveSlotTokenService.issue(
                f.owner.getId(), f.eventType.getId(), SLOT_START, SLOT_END,
                List.of(f.alice.getId(), f.bob.getId()));

        PublicHoldResponse hold = publicBookingService.hold(
                f.owner.getUsername(), f.eventType.getSlug(),
                new PublicBookRequest(SLOT_START, "guest@test.com", "Guest", token));
        assertThat(hold.bookingId()).isNotNull();
    }

    // ── 3. Readiness evaluation ───────────────────────────────────────────────

    @Test
    void readiness_allReady_publishableNoDegraded() {
        Fixture f = buildReadyFixture("allready");

        PublishReadinessResponse response = participantService.publishReadiness(
                f.owner.getId(), f.eventType.getId());

        assertThat(response.publishable()).isTrue();
        assertThat(response.degraded()).isFalse();
        assertThat(response.reasons()).isEmpty();
        assertThat(response.participants()).allMatch(p ->
                p.readinessStatus() == ParticipantReadinessStatus.READY);
        assertThat(response.participants()).allMatch(p ->
                p.readinessMessage() != null && !p.readinessMessage().isBlank());
    }

    @Test
    void readiness_participantNoCalendar_unpublishable() {
        User owner = createUser("ownernc@test.com", "ownernc");
        User alice = createUser("alicenc@test.com", "alicenc");
        EventType et = buildCollectiveEventType(owner, "nocalendar");
        insertParticipant(et.getId(), alice.getId());
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        // No calendar for alice

        PublishReadinessResponse response = participantService.publishReadiness(owner.getId(), et.getId());

        assertThat(response.publishable()).isFalse();
        assertThat(response.reasons()).isNotEmpty();
        EventTypeParticipantResponse aliceStatus = response.participants().stream()
                .filter(p -> p.userId().equals(alice.getId())).findFirst().orElseThrow();
        assertThat(aliceStatus.readinessStatus()).isEqualTo(ParticipantReadinessStatus.NO_CALENDAR);
        assertThat(aliceStatus.readinessMessage()).contains("calendar");
    }

    @Test
    void readiness_degradedCalendar_publishableButDegraded() {
        User owner = createUser("ownerdeg@test.com", "ownerdeg");
        User alice = createUser("alicedeg@test.com", "alicedeg");
        EventType et = buildCollectiveEventType(owner, "degraded");
        insertParticipant(et.getId(), alice.getId());
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertCalendarConnectionWithStatus(alice.getId(), "FAILED");

        PublishReadinessResponse response = participantService.publishReadiness(owner.getId(), et.getId());

        assertThat(response.publishable()).isTrue();
        assertThat(response.degraded()).isTrue();
        EventTypeParticipantResponse aliceStatus = response.participants().stream()
                .filter(p -> p.userId().equals(alice.getId())).findFirst().orElseThrow();
        assertThat(aliceStatus.readinessStatus()).isEqualTo(ParticipantReadinessStatus.DEGRADED_CALENDAR);
        assertThat(aliceStatus.readinessMessage()).contains("temporarily");
    }

    @Test
    void readiness_participantInactive_unpublishable() {
        User owner = createUser("ownerinact@test.com", "ownerinact");
        User alice = createUser("aliceinact@test.com", "aliceinact");
        EventType et = buildCollectiveEventType(owner, "inactive");
        insertParticipant(et.getId(), alice.getId());
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertCalendarConnectionWithWriteback(alice.getId());
        // Deactivate alice
        alice.setStatus(UserStatus.INACTIVE);
        userRepository.save(alice);

        PublishReadinessResponse response = participantService.publishReadiness(owner.getId(), et.getId());

        assertThat(response.publishable()).isFalse();
        EventTypeParticipantResponse aliceStatus = response.participants().stream()
                .filter(p -> p.userId().equals(alice.getId())).findFirst().orElseThrow();
        assertThat(aliceStatus.readinessStatus()).isEqualTo(ParticipantReadinessStatus.INACTIVE);
    }

    // ── 4. Auto-unpublish ─────────────────────────────────────────────────────

    @Test
    void autoUnpublish_whenCalendarRevoked() {
        Fixture f = buildReadyFixture("revoke");
        publishAll(f);
        assertThat(eventTypeRepository.findById(f.eventType.getId()).orElseThrow().isPublished()).isTrue();

        // Revoke alice's calendar
        revokeCalendar(f.alice.getId());

        publishReadinessService.applyAndEnforce(
                eventTypeRepository.findById(f.eventType.getId()).orElseThrow());

        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
        assertThat(outboxEventCount(f.eventType.getId(), EventTypeLifecycleOutboxPayload.EVENT_AUTO_UNPUBLISHED))
                .isEqualTo(1);
    }

    @Test
    void autoUnpublish_whenParticipantDeactivated() {
        Fixture f = buildReadyFixture("deact");
        publishAll(f);

        f.alice.setStatus(UserStatus.INACTIVE);
        userRepository.save(f.alice);

        publishReadinessService.applyAndEnforce(
                eventTypeRepository.findById(f.eventType.getId()).orElseThrow());

        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
        assertThat(outboxEventCount(f.eventType.getId(), EventTypeLifecycleOutboxPayload.EVENT_AUTO_UNPUBLISHED))
                .isEqualTo(1);
    }

    @Test
    void autoUnpublish_whenParticipantRemoved_leavingNoReadyParticipants() {
        Fixture f = buildReadyFixture("removed");
        publishAll(f);

        // Remove alice (bob has no calendar), so neither is ready after removal
        removeCalendar(f.bob.getId());
        // Replace to only bob (not ready)
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ?", f.eventType.getId());
        jdbc.update("INSERT INTO event_type_participants (id, event_type_id, user_id, display_order) VALUES (?,?,?,?)",
                UUID.randomUUID(), f.eventType.getId(), f.bob.getId(), 0);

        publishReadinessService.applyAndEnforce(
                eventTypeRepository.findById(f.eventType.getId()).orElseThrow());

        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
    }

    // ── 5. Degraded — stays published ────────────────────────────────────────

    @Test
    void degraded_transientFailure_staysPublished() {
        Fixture f = buildReadyFixture("deg2");
        publishAll(f);

        // Degrade alice's calendar to FAILED (transient)
        degradeCalendar(f.alice.getId(), "FAILED");

        publishReadinessService.applyAndEnforce(
                eventTypeRepository.findById(f.eventType.getId()).orElseThrow());

        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isTrue();

        PublishReadinessResponse response = participantService.publishReadiness(
                f.owner.getId(), f.eventType.getId());
        assertThat(response.degraded()).isTrue();
        assertThat(response.publishable()).isTrue();
    }

    @Test
    void degraded_errorStatus_staysPublished() {
        Fixture f = buildReadyFixture("deg3");
        publishAll(f);
        degradeCalendar(f.alice.getId(), "ERROR");

        publishReadinessService.applyAndEnforce(
                eventTypeRepository.findById(f.eventType.getId()).orElseThrow());

        EventType reloaded = eventTypeRepository.findById(f.eventType.getId()).orElseThrow();
        assertThat(reloaded.isPublished()).isTrue();
    }

    // ── 6. Historical integrity ───────────────────────────────────────────────

    @Test
    void participantRemoved_afterConfirmedBookings_assignmentsUnchanged() {
        Fixture f = buildReadyFixture("hist");
        publishAll(f);

        // Create a confirmed booking with alice + bob
        UUID bookingId = createConfirmedBookingWithAssignments(f);
        List<BookingAssignment> beforeRemoval = assignmentRepository.findAllByBookingId(bookingId);
        assertThat(beforeRemoval).hasSize(2);
        List<UUID> assignedParticipants = beforeRemoval.stream()
                .map(BookingAssignment::getParticipantUserId).toList();
        assertThat(assignedParticipants).containsExactlyInAnyOrder(f.alice.getId(), f.bob.getId());

        // Remove alice from the event type (only bob remains)
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ? AND user_id = ?",
                f.eventType.getId(), f.alice.getId());

        // Booking assignments must be unchanged
        List<BookingAssignment> afterRemoval = assignmentRepository.findAllByBookingId(bookingId);
        assertThat(afterRemoval).hasSize(2);
        assertThat(afterRemoval.stream().map(BookingAssignment::getParticipantUserId).toList())
                .containsExactlyInAnyOrder(f.alice.getId(), f.bob.getId());
    }

    @Test
    void participantDeactivated_afterConfirmedBookings_bookingsUnchanged() {
        Fixture f = buildReadyFixture("histdeact");
        publishAll(f);

        UUID bookingId = createConfirmedBookingWithAssignments(f);

        f.alice.setStatus(UserStatus.INACTIVE);
        userRepository.save(f.alice);

        // Booking still intact
        List<BookingAssignment> assignments = assignmentRepository.findAllByBookingId(bookingId);
        assertThat(assignments).hasSize(2);
    }

    // ── 7. API: readiness endpoint full contract ──────────────────────────────

    @Test
    void publishReadinessApi_returnsFullContract() {
        Fixture f = buildReadyFixture("api");

        PublishReadinessResponse response = participantService.publishReadiness(
                f.owner.getId(), f.eventType.getId());

        assertThat(response.published()).isFalse(); // starts unpublished
        assertThat(response.publishable()).isTrue();
        assertThat(response.degraded()).isFalse();
        assertThat(response.reasons()).isEmpty();
        assertThat(response.totalParticipants()).isEqualTo(2);
        assertThat(response.readyCount()).isEqualTo(2);
        assertThat(response.participants()).hasSize(2);
        // Each participant has a readinessMessage
        assertThat(response.participants()).allMatch(p ->
                p.readinessMessage() != null && !p.readinessMessage().isBlank());
    }

    @Test
    void publishReadinessApi_participantDetailIncludesAllFields() {
        Fixture f = buildReadyFixture("apidetail");
        publishAll(f);

        PublishReadinessResponse response = participantService.publishReadiness(
                f.owner.getId(), f.eventType.getId());

        EventTypeParticipantResponse alice = response.participants().get(0);
        assertThat(alice.userId()).isNotNull();
        assertThat(alice.readinessStatus()).isEqualTo(ParticipantReadinessStatus.READY);
        assertThat(alice.hasAvailabilityRules()).isTrue();
        assertThat(alice.hasActiveCalendar()).isTrue();
        assertThat(alice.hasWritebackCapability()).isTrue();
        assertThat(alice.readinessMessage()).isEqualTo(
                EventTypeParticipantResponse.buildReadinessMessage(ParticipantReadinessStatus.READY, alice.userName()));
    }

    // ── 8. Regression: other event kinds unaffected ───────────────────────────

    @Test
    void regression_oneOnOne_publishedByDefault_bookingUnaffected() {
        User host = createUser("host1on1@test.com", "host1on1reg");
        insertRule(host.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertCalendarConnectionWithWriteback(host.getId());
        EventType et = eventTypeRepository.save(EventType.builder()
                .userId(host.getId()).name("1on1 reg").slug("one-reg")
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30)).holdDuration(HOLD_DUR)
                .kind(EventKind.ONE_ON_ONE).capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE).build());

        assertThat(et.isPublished()).isTrue();
        // Hold succeeds without any token
        PublicHoldResponse hold = publicBookingService.hold(
                host.getUsername(), et.getSlug(),
                new PublicBookRequest(SLOT_START, "guest@test.com", "Guest", null));
        assertThat(hold.bookingId()).isNotNull();
    }

    @Test
    void regression_publishField_presentOnNonCollectiveEvents() {
        User host = createUser("hostreg@test.com", "hostreg");
        EventType et = eventTypeRepository.save(EventType.builder()
                .userId(host.getId()).name("Group reg").slug("group-reg")
                .duration(Duration.ofMinutes(30)).bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30)).minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30)).holdDuration(HOLD_DUR)
                .kind(EventKind.GROUP).capacity(5)
                .conferencingProvider(ConferencingProviderType.NONE).build());

        PublishReadinessResponse response = participantService.publishReadiness(host.getId(), et.getId());
        assertThat(response.published()).isTrue();
        assertThat(response.publishable()).isTrue();
        assertThat(response.degraded()).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record Fixture(User owner, User alice, User bob, EventType eventType) {}

    private Fixture buildReadyFixture(String suffix) {
        User owner = createUser("owner-" + suffix + "@test.com", "owner-" + suffix);
        User alice = createUser("alice-" + suffix + "@test.com", "alice-" + suffix);
        User bob   = createUser("bob-" + suffix + "@test.com", "bob-" + suffix);
        // Add to same team so team-pool validation passes
        addToTeam(owner.getId(), alice.getId(), bob.getId());

        EventType et = buildCollectiveEventType(owner, suffix);
        insertParticipant(et.getId(), alice.getId());
        insertParticipant(et.getId(), bob.getId());

        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertRule(bob.getId(),   "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());
        return new Fixture(owner, alice, bob, et);
    }

    private Fixture buildFixtureNoCalendar(String suffix) {
        User owner = createUser("owner-nc-" + suffix + "@test.com", "owner-nc-" + suffix);
        User alice = createUser("alice-nc-" + suffix + "@test.com", "alice-nc-" + suffix);
        addToTeam(owner.getId(), alice.getId());

        EventType et = buildCollectiveEventType(owner, "nc-" + suffix);
        insertParticipant(et.getId(), alice.getId());
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(17, 0));
        // No calendar for alice — not ready
        return new Fixture(owner, alice, alice, et);
    }

    private void publishAll(Fixture f) {
        eventTypeService.publish(f.owner.getId(), f.eventType.getId());
    }

    private EventType buildCollectiveEventType(User owner, String suffix) {
        return eventTypeRepository.save(EventType.builder()
                .userId(owner.getId())
                .name("Collective " + suffix)
                .slug("coll-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(HOLD_DUR)
                .kind(EventKind.COLLECTIVE)
                .capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE)
                .published(false) // explicit — COLLECTIVE starts unpublished
                .build());
    }

    private UUID createConfirmedBookingWithAssignments(Fixture f) {
        UUID bookingId = UUID.randomUUID();
        // Insert confirmed booking
        jdbc.update("""
                INSERT INTO bookings
                    (id, host_id, event_type_id, start_time, end_time, guest_email, guest_name,
                     status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'guest@test.com', 'Guest', 'CONFIRMED', 1, NOW(), NOW())
                """, bookingId, f.owner.getId(), f.eventType.getId(),
                java.sql.Timestamp.from(SLOT_START),
                java.sql.Timestamp.from(SLOT_END));
        // Insert booking assignments for alice and bob
        for (UUID participantId : List.of(f.alice.getId(), f.bob.getId())) {
            jdbc.update("""
                    INSERT INTO booking_assignments
                        (id, booking_id, participant_user_id, assignment_reason, created_at, updated_at)
                    VALUES (?, ?, ?, 'COLLECTIVE_ALL', NOW(), NOW())
                    """, UUID.randomUUID(), bookingId, participantId);
        }
        return bookingId;
    }

    private User createUser(String email, String username) {
        return userRepository.save(User.builder()
                .email(email).username(username).name(username)
                .timezone("UTC").status(UserStatus.ACTIVE).build());
    }

    private void addToTeam(UUID ownerId, UUID... memberIds) {
        UUID teamId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teams (id, owner_user_id, name, slug, created_at, updated_at)
                VALUES (?, ?, 'Team', 'team-' || ?, NOW(), NOW())
                """, teamId, ownerId, teamId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO team_members (id, team_id, user_id, role, joined_at)
                VALUES (?, ?, ?, 'OWNER', NOW())
                """, UUID.randomUUID(), teamId, ownerId);
        for (UUID memberId : memberIds) {
            jdbc.update("""
                    INSERT INTO team_members (id, team_id, user_id, role, joined_at)
                    VALUES (?, ?, ?, 'MEMBER', NOW())
                    """, UUID.randomUUID(), teamId, memberId);
        }
    }

    private void insertParticipant(UUID eventTypeId, UUID userId) {
        jdbc.update("""
                INSERT INTO event_type_participants (id, event_type_id, user_id, display_order)
                VALUES (?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), eventTypeId, userId);
    }

    private void insertRule(UUID userId, String day, LocalTime from, LocalTime to) {
        jdbc.update("""
                INSERT INTO availability_rules
                    (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?::time, ?::time, NOW(), NOW())
                """, userId, day, from.toString(), to.toString());
    }

    private void insertCalendarConnectionWithWriteback(UUID userId) {
        UUID connId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id, refresh_token_ciphertext,
                     last_token_expires_at, scopes, status, version, last_synced_at)
                VALUES (?,?,?,?,?,NOW() + INTERVAL '1 hour','{}'::text[],'ACTIVE',0,NOW())
                """, connId, userId, "GOOGLE", "ext-" + userId, "dummy-token");
        jdbc.update("""
                INSERT INTO calendar_connection_calendars
                    (id, connection_id, external_calendar_id, name, is_primary, is_selected,
                     sync_enabled, can_read, can_write, hidden)
                VALUES (?,?,?,?,true,true,true,true,true,false)
                """, UUID.randomUUID(), connId, "primary@" + userId, "Primary");
    }

    private void insertCalendarConnectionWithStatus(UUID userId, String status) {
        UUID connId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id, refresh_token_ciphertext,
                     last_token_expires_at, scopes, status, version, last_synced_at)
                VALUES (?,?,?,?,?,NOW() + INTERVAL '1 hour','{}'::text[],?,0,NOW())
                """, connId, userId, "GOOGLE", "ext-" + userId, "dummy-token", status);
        jdbc.update("""
                INSERT INTO calendar_connection_calendars
                    (id, connection_id, external_calendar_id, name, is_primary, is_selected,
                     sync_enabled, can_read, can_write, hidden)
                VALUES (?,?,?,?,true,true,true,true,false,false)
                """, UUID.randomUUID(), connId, "primary@" + userId, "Primary");
    }

    private void revokeCalendar(UUID userId) {
        jdbc.update("UPDATE calendar_connections SET status = 'REVOKED' WHERE user_id = ?", userId);
        // Remove writable inventory so hasWritebackCapability returns false
        jdbc.update("""
                UPDATE calendar_connection_calendars SET can_write = false
                WHERE connection_id IN (SELECT id FROM calendar_connections WHERE user_id = ?)
                """, userId);
    }

    private void removeCalendar(UUID userId) {
        jdbc.update("DELETE FROM calendar_connection_calendars WHERE connection_id IN "
                + "(SELECT id FROM calendar_connections WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM calendar_connections WHERE user_id = ?", userId);
    }

    private void degradeCalendar(UUID userId, String status) {
        jdbc.update("UPDATE calendar_connections SET status = ? WHERE user_id = ?", status, userId);
        jdbc.update("""
                UPDATE calendar_connection_calendars SET can_write = false
                WHERE connection_id IN (SELECT id FROM calendar_connections WHERE user_id = ?)
                """, userId);
    }

    private int outboxEventCount(UUID aggregateId, String eventType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, aggregateId, eventType);
        return count == null ? 0 : count;
    }
}
