package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.EventTypeParticipant;
import io.bunnycal.availability.dto.EventTypeParticipantResponse;
import io.bunnycal.availability.repository.EventTypeParticipantRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.team.repository.TeamMemberRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the event_type_participants surface: read, replace, and the "effective
 * participants" resolution that Phase 3 availability aggregation will consume.
 *
 * <p>Per-kind participant rules:
 * <ul>
 *   <li>ONE_ON_ONE / GROUP — exactly the owner. The stored list is ignored for these
 *       kinds; effective participants is always {@code [ownerUserId]}. Attempts to set
 *       a different list are rejected.</li>
 *   <li>ROUND_ROBIN / COLLECTIVE — 1..N participants. The owner is a normal participant
 *       and may remove themselves as long as at least one participant remains.</li>
 * </ul>
 *
 * <p>This service performs NO availability aggregation, assignment, or engine changes —
 * that is Phase 3/4. It is data modeling + validation only.
 *
 * <h2>Phase 3 participant ownership model (codified here, implemented in Phase 3)</h2>
 *
 * <p>Each participant contributes their OWN availability to the multi-participant
 * calculation, NOT the event type owner's:
 * <ul>
 *   <li><b>availability_rules</b> — per participant's own working hours (keyed by their
 *       user_id in the availability_rules table).</li>
 *   <li><b>availability_overrides</b> — per participant's own date overrides.</li>
 *   <li><b>calendar busy blocks</b> — from the participant's own connected calendars
 *       (their own calendar_connections rows, using ALL_CONNECTED semantics). The event
 *       type's availabilityCalendarsJson / availabilityMode is the owner's selection only
 *       and does NOT apply to participants.</li>
 *   <li><b>timezone</b> — each participant's own User.timezone. The engine must be called
 *       once per participant in that participant's timezone, and results converted to UTC
 *       before aggregation.</li>
 *   <li><b>bookings / sessions</b> — each participant's own booking conflicts.</li>
 * </ul>
 *
 * <p>Aggregation semantics (Phase 3):
 * <ul>
 *   <li>ROUND_ROBIN  — UNION of all eligible participant slot sets. A slot is available
 *       if at least one participant is free. Assignment follows the configured strategy
 *       (e.g. round-robin, least-busy).</li>
 *   <li>COLLECTIVE   — INTERSECTION of all eligible participant slot sets. A slot is
 *       available only if every participant is simultaneously free.</li>
 * </ul>
 *
 * <p>Participant eligibility for Phase 3 scheduling (enforced in Phase 3 SlotService
 * extension):
 * <ol>
 *   <li>User.status == ACTIVE</li>
 *   <li>Still a member of the owner's team pool (checked advisory; see note below)</li>
 *   <li>Has at least one availability_rule row (zero rules → no slots → excluded)</li>
 *   <li>For COLLECTIVE: must also have at least one ACTIVE calendar connection (a
 *       calendar-less participant would produce artificially wide intersection windows)</li>
 * </ol>
 *
 * <p><b>Team membership is a selection source only, never a scheduling invariant.</b>
 * {@code event_type_participants} is the scheduling source of truth. If a user is removed
 * from a team after being attached to an event type, their participant row is NOT pruned
 * automatically and scheduling behavior is unchanged. The {@code inTeam} flag on
 * {@link io.bunnycal.availability.dto.EventTypeParticipantResponse} is advisory metadata
 * for the UI (to flag stale participants) and must never gate slot generation.
 */
@Service
public class EventTypeParticipantService {

    private final EventTypeRepository eventTypeRepository;
    private final EventTypeParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;

    public EventTypeParticipantService(EventTypeRepository eventTypeRepository,
                                       EventTypeParticipantRepository participantRepository,
                                       UserRepository userRepository,
                                       TeamMemberRepository teamMemberRepository) {
        this.eventTypeRepository = eventTypeRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EventTypeParticipantResponse> listParticipants(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        List<UUID> effectiveIds = effectiveParticipantUserIds(eventType);
        return enrich(effectiveIds, eventType.getUserId(), actingUserId);
    }

    // ── Replace (PUT semantics) ────────────────────────────────────────────────

    @Transactional
    public List<EventTypeParticipantResponse> replaceParticipants(UUID actingUserId,
                                                                  UUID eventTypeId,
                                                                  List<UUID> requestedUserIds) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        EventKind kind = eventType.getKind();

        // De-duplicate while preserving submitted order (defines display/assignment order).
        List<UUID> ordered = dedupePreserveOrder(requestedUserIds == null ? List.of() : requestedUserIds);

        if (kind == EventKind.ONE_ON_ONE || kind == EventKind.GROUP) {
            // Locked to the owner. Accept an empty list (means "owner") or exactly [owner];
            // reject anything else so the UI cannot diverge single-host semantics.
            if (!(ordered.isEmpty() || (ordered.size() == 1 && ordered.get(0).equals(eventType.getUserId())))) {
                throw new CustomException(ErrorCode.PARTICIPANTS_NOT_ALLOWED_FOR_KIND,
                        kind + " event types are single-host and cannot have additional participants.");
            }
            // Single-host kinds keep zero stored rows; effective participant is the owner.
            participantRepository.deleteByEventTypeId(eventTypeId);
            return enrich(List.of(eventType.getUserId()), eventType.getUserId(), actingUserId);
        }

        // ROUND_ROBIN / COLLECTIVE: 1..N participants required.
        if (ordered.isEmpty()) {
            throw new CustomException(ErrorCode.PARTICIPANTS_REQUIRED,
                    kind + " event types require at least one participant.");
        }
        validateUsersExist(ordered);
        // Participants must be drawn from the owner's team pool (the owner is always in
        // their own pool). This guards against attaching arbitrary users.
        validateWithinTeamPool(eventType.getUserId(), ordered);

        participantRepository.deleteByEventTypeId(eventTypeId);
        List<EventTypeParticipant> rows = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            rows.add(EventTypeParticipant.builder()
                    .eventTypeId(eventTypeId)
                    .userId(ordered.get(i))
                    .displayOrder(i)
                    .build());
        }
        participantRepository.saveAll(rows);
        return enrich(ordered, eventType.getUserId(), actingUserId);
    }

    // ── Effective participants (consumed by Phase 3) ───────────────────────────

    /**
     * The user_ids whose availability/assignment this event type operates on.
     * ONE_ON_ONE / GROUP → always {@code [ownerUserId]} regardless of stored rows.
     * ROUND_ROBIN / COLLECTIVE → stored rows; falls back to {@code [ownerUserId]} only
     * if (unexpectedly) no rows exist, preserving a never-empty contract.
     *
     * <p><b>Team membership is deliberately NOT checked here.</b> The scheduling source
     * of truth is {@code event_type_participants}. If a participant was removed from the
     * team after being attached, their row persists and they remain schedulable. The
     * {@code inTeam} flag is advisory UI metadata only. Do not add team-membership
     * filtering to this method — that would silently remove participants from a live
     * event type and break existing bookings.
     */
    @Transactional(readOnly = true)
    public List<UUID> effectiveParticipantUserIds(EventType eventType) {
        EventKind kind = eventType.getKind();
        if (kind == EventKind.ONE_ON_ONE || kind == EventKind.GROUP) {
            return List.of(eventType.getUserId());
        }
        List<UUID> stored = participantRepository
                .findByEventTypeIdOrderByDisplayOrderAscCreatedAtAsc(eventType.getId())
                .stream()
                .map(EventTypeParticipant::getUserId)
                .toList();
        return stored.isEmpty() ? List.of(eventType.getUserId()) : stored;
    }

    // ── Validation helpers ─────────────────────────────────────────────────────

    private EventType requireOwnedEventType(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findById(eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        if (!eventType.getUserId().equals(actingUserId)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "You do not own this event type.");
        }
        return eventType;
    }

    private void validateUsersExist(List<UUID> userIds) {
        long found = userRepository.findAllById(userIds).size();
        if (found != userIds.size()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "One or more participants do not exist.");
        }
    }

    private void validateWithinTeamPool(UUID ownerUserId, List<UUID> userIds) {
        Set<UUID> pool = new HashSet<>(teamMemberRepository.findTeammateUserIds(ownerUserId));
        pool.add(ownerUserId); // owner is always allowed, even with no team
        for (UUID candidate : userIds) {
            if (!pool.contains(candidate)) {
                throw new CustomException(ErrorCode.PARTICIPANT_NOT_IN_TEAM,
                        "Participant " + candidate + " is not a member of any of your teams.");
            }
        }
    }

    // ── Enrichment ─────────────────────────────────────────────────────────────

    private List<EventTypeParticipantResponse> enrich(List<UUID> userIds, UUID ownerUserId, UUID actingUserId) {
        Map<UUID, User> usersById = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> usersById.put(u.getId(), u));

        Set<UUID> teamPool = new HashSet<>(teamMemberRepository.findTeammateUserIds(actingUserId));
        teamPool.add(actingUserId);

        List<EventTypeParticipantResponse> out = new ArrayList<>();
        for (int i = 0; i < userIds.size(); i++) {
            UUID uid = userIds.get(i);
            User u = usersById.get(uid);
            out.add(new EventTypeParticipantResponse(
                    uid,
                    u != null ? u.getName() : null,
                    u != null ? u.getEmail() : null,
                    u != null ? u.getProfileImageUrl() : null,
                    i,
                    uid.equals(ownerUserId),
                    teamPool.contains(uid)));
        }
        return out;
    }

    private static List<UUID> dedupePreserveOrder(List<UUID> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }
}
