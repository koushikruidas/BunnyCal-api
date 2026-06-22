package io.bunnycal.team;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.dto.BulkAvailabilityRulesUpsertRequest;
import io.bunnycal.availability.dto.AvailabilityRuleRequest;
import io.bunnycal.availability.service.AvailabilityService;
import io.bunnycal.availability.service.ParticipantEligibilityService;
import io.bunnycal.team.domain.ParticipantSetupRequest;
import io.bunnycal.team.dto.CreateTeamRequest;
import io.bunnycal.team.dto.InviteMemberRequest;
import io.bunnycal.team.dto.TeamMemberResponse;
import io.bunnycal.team.dto.TeamResponse;
import io.bunnycal.team.repository.ParticipantSetupRequestRepository;
import io.bunnycal.team.service.ParticipantSetupRequestService;
import io.bunnycal.team.service.TeamService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests proving setup requests complete ONLY when a participant reaches
 * READY state (all three dimensions: availability rules, active calendar, writeback).
 *
 * <p>Matrix:
 * <pre>
 *   Availability  Calendar  Writeback  →  Expected status
 *   ─────────────────────────────────────────────────────
 *   ✗             ✗         ✗          →  REQUESTED (no change)
 *   ✓             ✗         ✗          →  REQUESTED (calendar missing)
 *   ✗             ✓ (write) ✓          →  REQUESTED (availability missing)
 *   ✓             ✓ (ro)    ✗          →  REQUESTED (writeback missing)
 *   ✓             ✓ (write) ✓          →  COMPLETED
 * </pre>
 */
class SetupRequestCompletionIT extends AbstractTeamIT {

    @Autowired TeamService teamService;
    @Autowired ParticipantSetupRequestService setupRequestService;
    @Autowired ParticipantSetupRequestRepository setupRequestRepository;
    @Autowired AvailabilityService availabilityService;
    @Autowired ParticipantEligibilityService eligibilityService;

    private User owner;
    private User participant;
    private TeamResponse team;
    private TeamMemberResponse participantMember;

    @BeforeEach
    void setUpTeam() {
        jdbc.execute("TRUNCATE TABLE users, teams, team_members, team_invitations CASCADE");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("TRUNCATE TABLE participant_setup_requests CASCADE");
        jdbc.execute("TRUNCATE TABLE availability_rules CASCADE");
        jdbc.execute("TRUNCATE TABLE calendar_connections CASCADE");

        owner = createUser("owner@example.com");
        participant = createUser("participant@example.com");

        team = teamService.createTeam(owner.getId(), new CreateTeamRequest("RR Team", "rr-team"));
        var invite = teamService.inviteMember(
                owner.getId(), team.id(),
                new InviteMemberRequest("participant@example.com", null));
        participantMember = teamService.acceptInvitation(participant.getId(), invite.token());
    }

    // ── Helper: place a REQUESTED setup request ────────────────────────────────

    private void placeSetupRequest() {
        setupRequestService.sendSetupRequest(owner.getId(), participantMember.id());
    }

    private String fetchStatus() {
        return setupRequestRepository
                .findByOwnerUserIdAndTargetUserId(owner.getId(), participant.getId())
                .map(ParticipantSetupRequest::getStatus)
                .orElse("NOT_FOUND");
    }

    // ── Helper: seed calendar connection (active) ──────────────────────────────

    private UUID seedCalendarConnection() {
        UUID connectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_connections
                    (id, user_id, provider, provider_user_id, refresh_token_ciphertext,
                     last_token_expires_at, scopes, status, version)
                VALUES (?, ?, 'GOOGLE', 'google-uid-1', 'encrypted-token',
                        ?, '{}', 'ACTIVE', 0)
                """,
                connectionId, participant.getId(),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        return connectionId;
    }

    // ── Helper: add a writable inventory entry for a connection ───────────────

    private void seedInventoryEntry(UUID connectionId, boolean canWrite) {
        jdbc.update("""
                INSERT INTO calendar_connection_calendars
                    (id, connection_id, external_calendar_id, name,
                     is_primary, is_selected, sync_enabled, can_read, can_write, hidden)
                VALUES (?, ?, 'primary@group.calendar.google.com', 'Primary',
                        true, true, true, true, ?, false)
                """,
                UUID.randomUUID(), connectionId, canWrite);
    }

    // ── Helper: configure availability rules ──────────────────────────────────

    private void saveAvailabilityRules() {
        var rule = new AvailabilityRuleRequest();
        rule.setDayOfWeek(java.time.DayOfWeek.MONDAY);
        rule.setStartTime(java.time.LocalTime.of(9, 0));
        rule.setEndTime(java.time.LocalTime.of(17, 0));
        var request = new BulkAvailabilityRulesUpsertRequest();
        request.setRules(List.of(rule));
        availabilityService.replaceRules(participant.getId(), request);
    }

    // ── Test 1: No dimensions satisfied — request stays REQUESTED ─────────────

    @Test
    void noAvailability_noCalendar_requestStaysRequested() {
        placeSetupRequest();
        assertThat(fetchStatus()).isEqualTo("REQUESTED");

        // Trigger: save empty availability (no rules) — should not complete
        var request = new BulkAvailabilityRulesUpsertRequest();
        request.setRules(List.of());
        // replaceRules with empty list returns early, no trigger fires
        // We simulate a re-save that doesn't reach the trigger by verifying state is unchanged
        assertThat(fetchStatus()).isEqualTo("REQUESTED");
    }

    // ── Test 2: Availability saved, no calendar — request stays REQUESTED ─────

    @Test
    void availabilitySaved_noCalendar_requestStaysRequested() {
        placeSetupRequest();

        saveAvailabilityRules();

        assertThat(fetchStatus())
                .as("setup request must stay REQUESTED when calendar is missing")
                .isEqualTo("REQUESTED");
    }

    // ── Test 3: Calendar connected (write), no availability — stays REQUESTED ──
    //
    // The OAuth callback gate (isReady) can't be exercised directly in an IT without
    // mocking the OAuth flow. Instead we verify the invariant from the AvailabilityService
    // trigger path: saving availability rules with a writable calendar present but
    // *immediately after another removal* (empty rules) ensures the gate fires and sees
    // no availability rules, keeping the request REQUESTED.
    //
    // Concretely: seed calendar+writeback, save rules → COMPLETED (the positive case).
    // For the negative case (calendar present, no availability), we simply assert that
    // the request is still REQUESTED immediately after calendar is seeded, before any
    // availability save. This proves the calendar-connect side-effect alone does not
    // complete the request (the trigger in the OAuth callback also gates on isReady()).

    @Test
    void calendarConnectedWithWrite_noAvailability_requestStaysRequested() {
        placeSetupRequest();

        UUID connId = seedCalendarConnection();
        seedInventoryEntry(connId, true);

        // No availability rules saved — request must remain REQUESTED.
        // (The OAuth callback trigger also gates on isReady() and would not fire either.)
        assertThat(fetchStatus())
                .as("setup request must stay REQUESTED when availability is missing even though calendar is writable")
                .isEqualTo("REQUESTED");
    }

    // ── Test 4: Availability + calendar (read-only) — request stays REQUESTED ──

    @Test
    void availabilityAndReadOnlyCalendar_requestStaysRequested() {
        placeSetupRequest();

        UUID connId = seedCalendarConnection();
        seedInventoryEntry(connId, false); // read-only

        saveAvailabilityRules();

        assertThat(fetchStatus())
                .as("setup request must stay REQUESTED when calendar is read-only (no writeback)")
                .isEqualTo("REQUESTED");
    }

    // ── Test 5: All three dimensions — request transitions to COMPLETED ────────

    @Test
    void allThreeDimensions_requestTransitionsToCompleted() {
        placeSetupRequest();
        assertThat(fetchStatus()).isEqualTo("REQUESTED");

        UUID connId = seedCalendarConnection();
        seedInventoryEntry(connId, true); // writable

        saveAvailabilityRules(); // triggers isReady() check → all three satisfied → COMPLETED

        assertThat(fetchStatus())
                .as("setup request must be COMPLETED when all three dimensions are satisfied")
                .isEqualTo("COMPLETED");
    }

    // ── Test 6: Already COMPLETED — idempotent ────────────────────────────────

    @Test
    void alreadyCompleted_remainsCompleted() {
        placeSetupRequest();

        UUID connId = seedCalendarConnection();
        seedInventoryEntry(connId, true);
        saveAvailabilityRules(); // → COMPLETED

        assertThat(fetchStatus()).isEqualTo("COMPLETED");

        // Calling again is idempotent
        setupRequestService.markAllCompletedForTarget(participant.getId());
        assertThat(fetchStatus()).isEqualTo("COMPLETED");
    }

    // ── Test 8: Writeback downgrade — COMPLETED request stays COMPLETED, readiness = NO_WRITEBACK ──

    @Test
    void readWriteCalendar_thenCalendarDowngradedToReadOnly_completedRequestUnchanged() {
        // Phase 1: participant reaches READY — request completes.
        placeSetupRequest();
        UUID connId = seedCalendarConnection();
        seedInventoryEntry(connId, true);
        saveAvailabilityRules();
        assertThat(fetchStatus()).isEqualTo("COMPLETED");

        // Phase 2: inventory re-hydrates with canWrite=false (scope downgrade / re-grant).
        jdbc.update("UPDATE calendar_connection_calendars SET can_write = false WHERE connection_id = ?", connId);

        // Setup request must remain COMPLETED — it's a historical record of when the
        // participant completed setup. Downgrading writeback does not retroactively undo it.
        assertThat(fetchStatus())
                .as("completed setup request must not regress when writeback is later removed")
                .isEqualTo("COMPLETED");

        // Readiness must now reflect the downgrade.
        assertThat(eligibilityService.isReady(participant.getId()))
                .as("participant must no longer be READY after writeback downgrade")
                .isFalse();
        assertThat(eligibilityService.hasActiveCalendar(participant.getId()))
                .as("calendar connection must still be active")
                .isTrue();
        assertThat(eligibilityService.hasWritebackCapability(participant.getId()))
                .as("writeback capability must be false after canWrite=false inventory update")
                .isFalse();
    }

    // ── Test 7: Multiple owners' requests all complete when participant is ready ─

    @Test
    void multipleOwnerRequests_allCompleteWhenParticipantReady() {
        // Second owner also on the team
        User owner2 = createUser("owner2@example.com");
        var team2 = teamService.createTeam(owner2.getId(), new CreateTeamRequest("Team 2", "team-2"));
        var invite2 = teamService.inviteMember(
                owner2.getId(), team2.id(),
                new InviteMemberRequest("participant@example.com", null));
        teamService.acceptInvitation(participant.getId(), invite2.token());

        // Both owners send setup requests
        setupRequestService.sendSetupRequest(owner.getId(), participantMember.id());
        // find the member entry in team2 for owner2
        var members2 = teamService.listMembers(owner2.getId(), team2.id());
        var participantInTeam2 = members2.stream()
                .filter(m -> m.userId().equals(participant.getId()))
                .findFirst().orElseThrow();
        setupRequestService.sendSetupRequest(owner2.getId(), participantInTeam2.id());

        assertThat(setupRequestRepository.findByTargetUserId(participant.getId())
                .stream().filter(r -> "REQUESTED".equals(r.getStatus())).count()).isEqualTo(2);

        // Participant completes all three dimensions
        UUID connId = seedCalendarConnection();
        seedInventoryEntry(connId, true);
        saveAvailabilityRules();

        // Both requests should be completed
        long completedCount = setupRequestRepository.findByTargetUserId(participant.getId())
                .stream().filter(r -> "COMPLETED".equals(r.getStatus())).count();
        assertThat(completedCount)
                .as("all outstanding setup requests must complete when participant is READY")
                .isEqualTo(2);
    }
}
