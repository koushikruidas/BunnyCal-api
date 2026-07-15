package io.bunnycal.availability.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.avatar.ProfileAvatarService;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.EventTypeParticipant;
import io.bunnycal.availability.dto.EventTypeParticipantResponse;
import io.bunnycal.availability.dto.PublishReadinessResponse;
import io.bunnycal.availability.repository.EventTypeParticipantRepository;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.booking.service.BookingSchedulingProjectionResolver;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.team.repository.TeamMemberRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
 *   <li><b>calendar busy blocks</b> — from the participant's own calendars, specifically the ones
 *       they flagged as checking availability. That is their setting and it applies identically
 *       whoever is booking them: on their own event types, and on any team event they are part
 *       of.</li>
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
    private final ParticipantEligibilityService eligibilityService;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final OutboxPublisher outboxPublisher;
    private final TimeSource timeSource;
    private final ProfileAvatarService profileAvatarService;
    private final BookingSchedulingProjectionResolver projectionResolver;
    private final EventConferencingResolver conferencingResolver;
    // Injected lazily to break the circular dependency:
    // PublishReadinessService → EventTypeParticipantService → PublishReadinessService
    @Lazy
    @Autowired
    private PublishReadinessService publishReadinessService;

    public EventTypeParticipantService(EventTypeRepository eventTypeRepository,
                                       EventTypeParticipantRepository participantRepository,
                                       UserRepository userRepository,
                                       TeamMemberRepository teamMemberRepository,
                                       ParticipantEligibilityService eligibilityService,
                                       CalendarConnectionRepository calendarConnectionRepository,
                                       BookingAssignmentRepository bookingAssignmentRepository,
                                       OutboxPublisher outboxPublisher,
                                       TimeSource timeSource,
                                       ProfileAvatarService profileAvatarService,
                                       BookingSchedulingProjectionResolver projectionResolver,
                                       EventConferencingResolver conferencingResolver) {
        this.eventTypeRepository = eventTypeRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.eligibilityService = eligibilityService;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.bookingAssignmentRepository = bookingAssignmentRepository;
        this.outboxPublisher = outboxPublisher;
        this.timeSource = timeSource;
        this.profileAvatarService = profileAvatarService;
        this.projectionResolver = projectionResolver;
        this.conferencingResolver = conferencingResolver;
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

        // Validate conferencing/participant-pool compatibility for RR:
        // the selected conferencing provider must be supportable by at least one participant.
        if (kind == EventKind.ROUND_ROBIN) {
            validateConferencingForRoundRobinParticipants(eventType.getConferencingProvider(), ordered);
        }

        // Capture previous roster before deletion (needed for COLLECTIVE removed-participant warning).
        List<UUID> previousParticipantIds = participantRepository
                .findByEventTypeIdOrderByDisplayOrderAscCreatedAtAsc(eventTypeId)
                .stream().map(EventTypeParticipant::getUserId).toList();

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

        if (kind == EventKind.COLLECTIVE) {
            // Detect removed participants who have future confirmed bookings.
            Set<UUID> removedIds = new HashSet<>(previousParticipantIds);
            removedIds.removeAll(new HashSet<>(ordered));
            for (UUID removedUserId : removedIds) {
                long futureCount = bookingAssignmentRepository
                        .countFutureConfirmedByParticipantAndEventType(removedUserId, eventTypeId, Instant.now());
                if (futureCount > 0) {
                    EventTypeLifecycleOutboxPayload warningPayload = new EventTypeLifecycleOutboxPayload(
                            eventTypeId, eventType.getName(), eventType.getUserId(),
                            "Participant removed with " + futureCount + " future confirmed bookings. "
                                    + "Those bookings are not affected.",
                            List.of(new EventTypeLifecycleOutboxPayload.ParticipantStatusSnapshot(
                                    removedUserId, null, "REMOVED", "Removed participant has future bookings.")),
                            timeSource.now());
                    outboxPublisher.publish(
                            EventTypeLifecycleOutboxPayload.AGGREGATE_TYPE,
                            eventTypeId,
                            new OutboxPayloadEnvelope(
                                    UUID.randomUUID().toString(),
                                    EventTypeLifecycleOutboxPayload.EVENT_PARTICIPANT_REMOVED_WITH_FUTURE_BOOKINGS,
                                    1,
                                    warningPayload));
                }
            }
            // Enforce publish-state readiness after roster change.
            publishReadinessService.applyAndEnforce(eventType);
        }

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

    // ── Bulk readiness probe (pre-event onboarding) ───────────────────────────

    /**
     * Returns readiness metadata for an arbitrary list of user IDs without
     * requiring an existing event type. Used by the RR onboarding wizard to
     * show participant readiness before the event is created.
     *
     * <p>Callers must ensure every user ID belongs to the acting user's team
     * pool; this method validates that constraint and rejects unknown users.
     */
    @Transactional(readOnly = true)
    public List<EventTypeParticipantResponse> checkReadiness(UUID actingUserId, List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        List<UUID> ordered = dedupePreserveOrder(userIds);
        validateWithinTeamPool(actingUserId, ordered);
        return enrich(ordered, actingUserId, actingUserId);
    }

    // ── Publish readiness ─────────────────────────────────────────────────────

    /**
     * Returns readiness for a COLLECTIVE or ROUND_ROBIN event type.
     * Delegates to {@link PublishReadinessService} as the single authority.
     */
    @Transactional(readOnly = true)
    public PublishReadinessResponse publishReadiness(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = requireOwnedEventType(actingUserId, eventTypeId);
        return publishReadinessService.publishReadinessResponse(eventType);
    }

    // ── Readiness delegation (called by PublishReadinessService) ──────────────

    /**
     * Returns enriched participant responses for readiness evaluation.
     * Exposed as package-friendly for {@link PublishReadinessService}.
     */
    @Transactional(readOnly = true)
    public List<EventTypeParticipantResponse> enrichForReadiness(List<UUID> userIds, UUID ownerUserId) {
        return enrich(userIds, ownerUserId, ownerUserId);
    }

    // ── Validation helpers ─────────────────────────────────────────────────────

    private EventType requireOwnedEventType(UUID actingUserId, UUID eventTypeId) {
        EventType eventType = eventTypeRepository.findByIdAndDeletedAtIsNull(eventTypeId)
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

            ParticipantEligibilityResult eligibility = eligibilityService.checkForRoundRobin(uid);
            boolean hasCalendar = eligibilityService.hasActiveCalendar(uid);
            boolean hasWriteback = hasCalendar && eligibilityService.hasWritebackCapability(uid);
            String calendarProvider = hasCalendar ? eligibilityService.activeCalendarProvider(uid) : null;
            ParticipantReadinessStatus readiness = computeReadiness(eligibility, hasCalendar, hasWriteback, uid);

            boolean hasRules = eligibility.reason() == ParticipantEligibilityReason.ACTIVE
                    || eligibility.reason() == ParticipantEligibilityReason.NO_ACTIVE_CALENDAR;

            // Teams capability: requires an active Microsoft work/school (Entra) account.
            // Consumer MSA accounts (Outlook.com) cannot host native Teams meetings. A user may
            // hold several Microsoft accounts, so any one work/school account is enough.
            boolean supportsNativeTeams = calendarConnectionRepository
                    .findByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
                            uid, CalendarProviderType.MICROSOFT, CalendarConnectionStatus.ACTIVE)
                    .stream()
                    .anyMatch(conn -> !MicrosoftAccountClassifier.isConsumerMsa(conn));

            String displayName = u != null ? u.getName() : null;
            out.add(new EventTypeParticipantResponse(
                    uid,
                    displayName,
                    u != null ? u.getEmail() : null,
                    u != null ? profileAvatarService.resolveProfileImageUrl(u) : null,
                    i,
                    uid.equals(ownerUserId),
                    teamPool.contains(uid),
                    eligibility.eligible(),
                    eligibility.reason(),
                    hasRules,
                    hasCalendar,
                    calendarProvider,
                    hasWriteback,
                    readiness,
                    EventTypeParticipantResponse.buildReadinessMessage(readiness, displayName),
                    supportsNativeTeams));
        }
        return out;
    }

    private ParticipantReadinessStatus computeReadiness(
            ParticipantEligibilityResult eligibility, boolean hasCalendar, boolean hasWriteback, UUID userId) {
        return switch (eligibility.reason()) {
            case USER_INACTIVE -> ParticipantReadinessStatus.INACTIVE;
            case USER_DELETED, USER_NOT_FOUND -> ParticipantReadinessStatus.REVOKED;
            case NO_AVAILABILITY_RULES -> ParticipantReadinessStatus.NO_AVAILABILITY;
            case NO_ACTIVE_CALENDAR -> {
                // Distinguish transient failure (DEGRADED_CALENDAR) from structural absence (NO_CALENDAR).
                if (eligibilityService.hasDegradedCalendar(userId)) {
                    yield ParticipantReadinessStatus.DEGRADED_CALENDAR;
                }
                yield ParticipantReadinessStatus.NO_CALENDAR;
            }
            case ACTIVE -> {
                if (!hasCalendar) {
                    if (eligibilityService.hasDegradedCalendar(userId)) {
                        yield ParticipantReadinessStatus.DEGRADED_CALENDAR;
                    }
                    yield ParticipantReadinessStatus.NO_CALENDAR;
                }
                if (!hasWriteback) yield ParticipantReadinessStatus.NO_WRITEBACK;
                yield ParticipantReadinessStatus.READY;
            }
        };
    }

    private static List<UUID> dedupePreserveOrder(List<UUID> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }

    /**
     * Validates that every member of a round-robin pool can host the event's meeting link.
     *
     * <p>The rotation assigns the meeting to whoever is next, and that member's own calendar mints
     * the link — so the requirement is on all of them, not on any of them.
     *
     * <p><b>This check can go stale, and cannot be made not to.</b> It runs when the pool is built,
     * but each member's capability depends on <em>their own</em> write-back calendar, which they can
     * change afterwards without ever seeing this event type. A member who moves from Google to
     * Outlook silently stops being able to host a Meet for a pool someone else configured months ago.
     * The booking-time guard ({@code BookingConferencingCapabilityGuard}) is what actually protects
     * the guest; this exists to fail early, while the person building the pool is still looking at it.
     */
    private void validateConferencingForRoundRobinParticipants(
            ConferencingProviderType conferencing, List<UUID> participantIds) {
        // Every member must be able to host, not just one of them: the rotation assigns the meeting
        // to whoever is next, not to whoever happens to be capable. A pool with one Outlook-only
        // member, offered Google Meet, produces bookings with no join link whenever their turn
        // comes up.
        //
        // Capability is resolved per member against THEIR OWN settings — the event type stores
        // DEFAULT, and each member's default resolves against the calendar they write to. So a
        // member who has a Google account connected but sends their bookings to Outlook cannot host
        // a Meet, and this is the check that says so.
        List<UUID> incapable = participantIds.stream()
                .filter(uid -> {
                    ConferencingProviderType resolved = conferencingResolver.resolve(uid, conferencing);
                    if (resolved == null || !resolved.requiresCalendarProvider()) {
                        return false;   // Zoom, custom, none: no calendar needed.
                    }
                    CalendarConnection writeback = projectionResolver.writebackConnection(uid).orElse(null);
                    return !EventConferencingResolver.canServe(writeback, resolved);
                })
                .toList();

        if (!incapable.isEmpty()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Every team member needs a calendar that can create this meeting link. "
                            + describeIncapableParticipants(incapable) + " cannot host it. "
                            + "They can fix this in their own calendar settings, or you can use Zoom.");
        }
    }

    /** Names the members who block the provider, so the caller can act rather than guess. */
    private String describeIncapableParticipants(List<UUID> userIds) {
        List<String> names = userRepository.findAllById(userIds).stream()
                .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                .filter(n -> n != null && !n.isBlank())
                .toList();
        if (names.isEmpty()) {
            return userIds.size() == 1 ? "One team member" : userIds.size() + " team members";
        }
        return String.join(", ", names);
    }
}
