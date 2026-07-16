package io.bunnycal.availability.participant;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.AvailabilityStatus;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.availability.service.SlotService;
import java.time.Duration;
import java.time.LocalDate;
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
 * Integration tests for Phase 3A ROUND_ROBIN availability aggregation (UNION semantics).
 *
 * <p>Test cases F-M per spec:
 * <ul>
 *   <li>F: One eligible participant → same slots as single-user</li>
 *   <li>G: Two participants non-overlapping → UNION = combined slots</li>
 *   <li>H: Two participants overlapping same slot → deduplicated (slot appears once)</li>
 *   <li>I: One eligible + one ineligible (no rules) → only eligible participant's slots</li>
 *   <li>J: All participants ineligible → NO_ELIGIBLE_PARTICIPANTS status, empty slots</li>
 *   <li>K: Participant timezone respected (participant in IST vs UTC)</li>
 *   <li>L: Participant override respected (override blocks a day → no slots for that participant)</li>
 *   <li>M: Participant calendar busy respected (calendar event blocks a slot → absent from contribution)</li>
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
class RoundRobinAvailabilityIT {

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
    @Autowired SlotService slotService;
    @Autowired EventTypeParticipantService participantService;

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 8, 3);  // Monday

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, teams, team_members, team_invitations, "
                + "event_types, event_type_participants, availability_rules, availability_overrides, "
                + "calendar_connections, calendar_events, bookings CASCADE");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User createUser(String email, String timezone) {
        return userRepository.save(User.builder()
                .email(email).name("U " + email).timezone(timezone).build());
    }

    private EventType createRrEventType(UUID ownerId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(ownerId)
                .name("RR event")
                .slug("rr-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ROUND_ROBIN)
                .capacity(1)
                .build());
    }

    /**
     * Inserts an availability rule for the given user directly via JDBC.
     *
     * @param userId the user
     * @param dayOfWeek e.g. "MONDAY"
     * @param startTime e.g. LocalTime.of(9, 0)
     * @param endTime e.g. LocalTime.of(11, 0)
     */
    private void insertRule(UUID userId, String dayOfWeek, LocalTime startTime, LocalTime endTime) {
        jdbc.update(
                "INSERT INTO availability_rules (id, user_id, day_of_week, start_time, end_time) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), userId, dayOfWeek, startTime, endTime);
    }

    /**
     * Inserts an availability override blocking a user on a given date.
     */
    private void insertBlockingOverride(UUID userId, LocalDate date) {
        jdbc.update(
                "INSERT INTO availability_overrides (id, user_id, date, is_available) VALUES (?,?,?,?)",
                UUID.randomUUID(), userId, date, false);
    }

    /**
     * Inserts an ACTIVE calendar connection for the user. Returns the connection id.
     * The scopes column is text[] in PostgreSQL, so we use a native cast.
     */
    private UUID insertCalendarConnection(UUID userId) {
        UUID connId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO calendar_connections "
                        + "(id, user_id, provider, provider_user_id, refresh_token_ciphertext, last_token_expires_at, scopes, status, version) "
                        + "VALUES (?,?,?,?,?,NOW() + INTERVAL '1 hour','{}'::text[],?,0)",
                connId, userId, "GOOGLE", "ext-" + userId, "dummy-token",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO calendar_connection_calendars "
                        + "(id, connection_id, external_calendar_id, name, is_primary, calendar_role, "
                        + "is_selected, checks_availability, can_read, can_write, hidden) "
                        + "VALUES (?,?,?,?,true,'PRIMARY',true,true,true,true,false)",
                UUID.randomUUID(), connId, "primary", "Primary");
        return connId;
    }

    /**
     * Inserts a calendar event (busy block) for a given connection.
     *
     * @param userId the user owning the event
     * @param connId the connection id
     * @param startsAt ISO instant string, e.g. "2026-06-15T09:00:00Z"
     * @param endsAt ISO instant string, e.g. "2026-06-15T09:30:00Z"
     */
    private void insertCalendarEvent(UUID userId, UUID connId, String startsAt, String endsAt) {
        jdbc.update(
                "INSERT INTO calendar_events "
                        + "(id, user_id, connection_id, provider, external_event_id, starts_at, ends_at, cancelled, deleted, blocks_availability) "
                        + "VALUES (?,?,?,?,?,?::timestamptz,?::timestamptz,false,false,true)",
                UUID.randomUUID(), userId, connId, "GOOGLE", "ext-" + UUID.randomUUID(),
                startsAt, endsAt);
    }

    /**
     * Attaches a participant to an event type (sets participant list to exactly [participantId]).
     * For RR with a single participant this is the owner; for multi-participant tests use
     * the team-based replaceParticipants pathway via raw JDBC instead.
     */
    private void setParticipants(UUID ownerId, UUID eventTypeId, List<UUID> participantIds) {
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ?", eventTypeId);
        for (int i = 0; i < participantIds.size(); i++) {
            jdbc.update(
                    "INSERT INTO event_type_participants (id, event_type_id, user_id, display_order) VALUES (?,?,?,?)",
                    UUID.randomUUID(), eventTypeId, participantIds.get(i), i);
        }
    }

    // ── Test F: One eligible participant → same slots as single-user ───────────

    @Test
    void testF_oneEligibleParticipant_returnsSameAsSingleUser() {
        User owner = createUser("owner-f@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        // Set owner as the only participant.
        setParticipants(owner.getId(), et.getId(), List.of(owner.getId()));

        // Add availability rule: Monday 09:00-11:00 UTC.
        insertRule(owner.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        // Add calendar connection so status is AVAILABLE (not degraded).
        insertCalendarConnection(owner.getId());

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Expect 4 slots: 09:00, 09:30, 10:00, 10:30 UTC.
        assertThat(response.slots()).hasSize(4);
        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(response.degraded()).isFalse();
        // Verify chronological order.
        for (int i = 1; i < response.slots().size(); i++) {
            assertThat(response.slots().get(i).start()).isAfter(response.slots().get(i - 1).start());
        }
    }

    // ── Test G: Two participants non-overlapping → UNION = combined slots ──────

    @Test
    void testG_twoParticipants_nonOverlapping_unionsCombinedSlots() {
        User owner = createUser("owner-g@test.com", "UTC");
        User alice = createUser("alice-g@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(owner.getId(), alice.getId()));

        // Owner available 09:00-10:00, Alice available 11:00-12:00.
        insertRule(owner.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(11, 0), LocalTime.of(12, 0));
        // Add calendar connections so status is AVAILABLE (not degraded).
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Owner contributes 09:00, 09:30; Alice contributes 11:00, 11:30 → 4 slots total.
        assertThat(response.slots()).hasSize(4);
        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        // Slots should be chronologically ordered.
        List<java.time.Instant> starts = response.slots().stream()
                .map(s -> s.start())
                .toList();
        assertThat(starts).isSortedAccordingTo(java.time.Instant::compareTo);
    }

    // ── Test H: Two participants sharing the same slot → deduplicated ──────────

    @Test
    void testH_twoParticipants_sameSlot_deduplicatedToOneSlot() {
        User owner = createUser("owner-h@test.com", "UTC");
        User alice = createUser("alice-h@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(owner.getId(), alice.getId()));

        // Both available 09:00-10:00 UTC → same 2 slots would be generated for each.
        insertRule(owner.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        // Add calendar connections so status is AVAILABLE (not degraded).
        insertCalendarConnection(owner.getId());
        insertCalendarConnection(alice.getId());

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Slots are deduplicated: 09:00 and 09:30 appear exactly once.
        assertThat(response.slots()).hasSize(2);
        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    // ── Test I: One eligible + one ineligible (no rules) → only eligible slots ─

    @Test
    void testI_oneEligibleOneineligible_onlyEligibleSlots() {
        User owner = createUser("owner-i@test.com", "UTC");
        User noRules = createUser("norules-i@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(owner.getId(), noRules.getId()));

        // Owner has rules; noRules has no rules (ineligible).
        insertRule(owner.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        // noRules: no rules inserted.
        // Add calendar connection for owner so status is AVAILABLE (not degraded).
        insertCalendarConnection(owner.getId());

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Only owner's slots: 09:00, 09:30.
        assertThat(response.slots()).hasSize(2);
        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    // ── Test J: All participants ineligible → NO_ELIGIBLE_PARTICIPANTS ─────────

    @Test
    void testJ_allParticipantsIneligible_returnsNoEligibleParticipants() {
        User owner = createUser("owner-j@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(owner.getId()));
        // No availability rules inserted → owner is ineligible.

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        assertThat(response.slots()).isEmpty();
        assertThat(response.status()).isEqualTo(AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS);
        assertThat(response.degraded()).isTrue();
    }

    // ── Test K: Participant timezone respected ─────────────────────────────────

    @Test
    void testK_participantTimezone_slotsBoundedByParticipantWorkingHours() {
        User owner = createUser("owner-k@test.com", "UTC");
        // IST is UTC+5:30
        User istParticipant = createUser("ist-k@test.com", "Asia/Kolkata");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(istParticipant.getId()));

        // Available 09:00-10:00 IST = 03:30-04:30 UTC on TEST_DATE (Monday 2026-08-03).
        insertRule(istParticipant.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        // Add calendar connection so status is AVAILABLE (not degraded).
        insertCalendarConnection(istParticipant.getId());

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Should have 2 slots at 03:30 UTC and 04:00 UTC.
        assertThat(response.slots()).hasSize(2);
        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);

        java.time.Instant expectedFirst = java.time.Instant.parse("2026-08-03T03:30:00Z");
        java.time.Instant expectedSecond = java.time.Instant.parse("2026-08-03T04:00:00Z");
        assertThat(response.slots().get(0).start()).isEqualTo(expectedFirst);
        assertThat(response.slots().get(1).start()).isEqualTo(expectedSecond);
    }

    // ── Test L: Participant override blocks the day → no slots from that participant ─

    @Test
    void testL_participantOverride_blocksDay_noSlotsFromParticipant() {
        User owner = createUser("owner-l@test.com", "UTC");
        User overrideUser = createUser("override-l@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(overrideUser.getId()));

        // Has rules but also a blocking override for TEST_DATE.
        insertRule(overrideUser.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertBlockingOverride(overrideUser.getId(), TEST_DATE);

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Override blocks the day → no slots from this participant.
        // Single participant with no slots → treated as no eligible participants (they are eligible but contribute 0 slots).
        // With 1 participant, unionSlots will be empty → NO_SLOTS_AVAILABLE.
        assertThat(response.slots()).isEmpty();
        assertThat(response.status()).isEqualTo(AvailabilityStatus.NO_SLOTS_AVAILABLE);
        assertThat(response.degraded()).isFalse();
    }

    // ── Test M: Participant calendar busy blocks a slot ────────────────────────

    @Test
    void testM_calendarBusy_blocksSlot_absentFromParticipantContribution() {
        User owner = createUser("owner-m@test.com", "UTC");
        EventType et = createRrEventType(owner.getId());

        setParticipants(owner.getId(), et.getId(), List.of(owner.getId()));

        // Available 09:00-11:00 UTC.
        insertRule(owner.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));

        // Active calendar with a busy event blocking 09:00-09:30.
        UUID connId = insertCalendarConnection(owner.getId());
        insertCalendarEvent(owner.getId(), connId, "2026-08-03T09:00:00Z", "2026-08-03T09:30:00Z");

        SlotResponse response = slotService.getSlots(new SlotRequest(owner.getId(), et.getId(), TEST_DATE));

        // Expect 09:00 slot to be absent; 09:30, 10:00, 10:30 present.
        assertThat(response.slots()).hasSize(3);
        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        // 09:00 slot should not be present.
        java.time.Instant nineAm = java.time.Instant.parse("2026-08-03T09:00:00Z");
        assertThat(response.slots().stream().map(s -> s.start()).toList())
                .doesNotContain(nineAm);
    }
}
