package io.bunnycal.availability.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.EventTypeParticipantResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.domain.TeamRole;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamInvitationResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.service.TeamService;
import java.time.Duration;
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
class EventTypeParticipantIT {

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
    @Autowired EventTypeParticipantService participantService;
    @Autowired TeamService teamService;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, teams, team_members, team_invitations, "
                + "event_types, event_type_participants CASCADE");
    }

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email).name("U " + email).timezone("UTC").build());
    }

    private EventType createEventType(UUID ownerId, EventKind kind) {
        return eventTypeRepository.save(EventType.builder()
                .userId(ownerId)
                .name(kind + " event")
                .slug(kind.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(kind)
                .capacity(kind == EventKind.GROUP ? 5 : 1)
                .build());
    }

    /** Add a user to the owner's team so they are within the selectable pool. */
    private void addToOwnersTeam(UUID ownerId, User member) {
        TeamResponse team = teamService.createTeam(ownerId, new CreateTeamRequest("Pool " + UUID.randomUUID(), null));
        TeamInvitationResponse invite = teamService.inviteMember(
                ownerId, team.id(), new InviteMemberRequest(member.getEmail(), TeamRole.MEMBER));
        teamService.acceptInvitation(member.getId(), invite.token());
    }

    // ── ONE_ON_ONE / GROUP: locked to owner ────────────────────────────────────

    @Test
    void oneOnOne_defaultsToOwner_andRejectsExtraParticipants() {
        User owner = createUser("owner@test.com");
        User other = createUser("other@test.com");
        EventType et = createEventType(owner.getId(), EventKind.ONE_ON_ONE);

        List<EventTypeParticipantResponse> listed = participantService.listParticipants(owner.getId(), et.getId());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).userId()).isEqualTo(owner.getId());
        assertThat(listed.get(0).isOwner()).isTrue();

        // Setting [owner] is accepted (no-op).
        participantService.replaceParticipants(owner.getId(), et.getId(), List.of(owner.getId()));

        // Setting an extra participant is rejected.
        assertThatThrownBy(() -> participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(owner.getId(), other.getId())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARTICIPANTS_NOT_ALLOWED_FOR_KIND);
    }

    @Test
    void group_lockedToOwner() {
        User owner = createUser("owner@test.com");
        User other = createUser("other@test.com");
        EventType et = createEventType(owner.getId(), EventKind.GROUP);

        assertThat(participantService.effectiveParticipantUserIds(et)).containsExactly(owner.getId());

        assertThatThrownBy(() -> participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(other.getId())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARTICIPANTS_NOT_ALLOWED_FOR_KIND);
    }

    // ── ROUND_ROBIN / COLLECTIVE: 1..N ──────────────────────────────────────────

    @Test
    void roundRobin_acceptsMultipleTeammates_inOrder() {
        User owner = createUser("owner@test.com");
        User alice = createUser("alice@test.com");
        User bob = createUser("bob@test.com");
        addToOwnersTeam(owner.getId(), alice);
        addToOwnersTeam(owner.getId(), bob);
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);

        List<EventTypeParticipantResponse> result = participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(bob.getId(), owner.getId(), alice.getId()));

        assertThat(result).extracting(EventTypeParticipantResponse::userId)
                .containsExactly(bob.getId(), owner.getId(), alice.getId());
        assertThat(result).extracting(EventTypeParticipantResponse::displayOrder)
                .containsExactly(0, 1, 2);

        assertThat(participantService.effectiveParticipantUserIds(
                eventTypeRepository.findById(et.getId()).orElseThrow()))
                .containsExactly(bob.getId(), owner.getId(), alice.getId());
    }

    @Test
    void collective_requiresAtLeastOneParticipant() {
        User owner = createUser("owner@test.com");
        EventType et = createEventType(owner.getId(), EventKind.COLLECTIVE);

        assertThatThrownBy(() -> participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARTICIPANTS_REQUIRED);
    }

    @Test
    void roundRobin_ownerCanRemoveSelf_ifOthersRemain() {
        User owner = createUser("owner@test.com");
        User alice = createUser("alice@test.com");
        addToOwnersTeam(owner.getId(), alice);
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);

        List<EventTypeParticipantResponse> result = participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(alice.getId()));

        assertThat(result).extracting(EventTypeParticipantResponse::userId).containsExactly(alice.getId());
        // Owner retains management rights via event_types.user_id even though not a participant.
        assertThat(eventTypeRepository.findById(et.getId()).orElseThrow().getUserId()).isEqualTo(owner.getId());
    }

    @Test
    void roundRobin_rejectsNonTeamUser() {
        User owner = createUser("owner@test.com");
        User stranger = createUser("stranger@test.com"); // not in owner's team
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);

        assertThatThrownBy(() -> participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(stranger.getId())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARTICIPANT_NOT_IN_TEAM);
    }

    @Test
    void roundRobin_ownerAlwaysAllowedEvenWithNoTeam() {
        User owner = createUser("owner@test.com");
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);

        List<EventTypeParticipantResponse> result = participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(owner.getId()));
        assertThat(result).extracting(EventTypeParticipantResponse::userId).containsExactly(owner.getId());
    }

    @Test
    void replace_dedupesDuplicateUserIds() {
        User owner = createUser("owner@test.com");
        User alice = createUser("alice@test.com");
        addToOwnersTeam(owner.getId(), alice);
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);

        List<EventTypeParticipantResponse> result = participantService.replaceParticipants(
                owner.getId(), et.getId(), List.of(alice.getId(), alice.getId(), owner.getId()));
        assertThat(result).extracting(EventTypeParticipantResponse::userId)
                .containsExactly(alice.getId(), owner.getId());
    }

    // ── Validation: team removal does NOT detach existing participants ──────────

    @Test
    void participantRemainsAttached_afterTeamRemoval() {
        User owner = createUser("owner@test.com");
        User alice = createUser("alice@test.com");
        addToOwnersTeam(owner.getId(), alice);
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);
        participantService.replaceParticipants(owner.getId(), et.getId(), List.of(owner.getId(), alice.getId()));

        // Simulate alice leaving every team: delete her team_members rows directly.
        jdbc.update("DELETE FROM team_members WHERE user_id = ?", alice.getId());

        // Attachment persists — scheduling never depends on team membership.
        List<EventTypeParticipantResponse> listed = participantService.listParticipants(owner.getId(), et.getId());
        assertThat(listed).extracting(EventTypeParticipantResponse::userId)
                .containsExactly(owner.getId(), alice.getId());
        // Advisory inTeam flag now reflects that alice is no longer a teammate.
        EventTypeParticipantResponse aliceRow = listed.stream()
                .filter(p -> p.userId().equals(alice.getId())).findFirst().orElseThrow();
        assertThat(aliceRow.inTeam()).isFalse();
    }

    // ── Authorization ───────────────────────────────────────────────────────────

    @Test
    void nonOwner_cannotModifyParticipants() {
        User owner = createUser("owner@test.com");
        User stranger = createUser("stranger@test.com");
        EventType et = createEventType(owner.getId(), EventKind.ROUND_ROBIN);

        assertThatThrownBy(() -> participantService.listParticipants(stranger.getId(), et.getId()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
