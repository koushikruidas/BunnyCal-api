package io.bunnycal.session.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupEventReservationWindow;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.booking.outbox.OutboxPayloadEnvelope;
import io.bunnycal.booking.service.BookingActionType;
import io.bunnycal.booking.service.GuestCapabilityTokenService;
import io.bunnycal.booking.service.TokenCreatorType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.AuthProvider;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.exception.RegistrationHoldActiveException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.RegistrationStatus;
import io.bunnycal.session.domain.SessionDetachedReason;
import io.bunnycal.session.domain.SessionRegistration;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final EventSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final OutboxPublisher outboxPublisher;
    private final GuestCapabilityTokenService tokenService;
    private final SlotCacheVersionService slotCacheVersionService;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final TimeSource timeSource;
    private final SessionLineageResolver lineageResolver;
    private final RescheduleConflictService rescheduleConflictService;

    public SessionService(EventSessionRepository sessionRepository,
                          SessionRegistrationRepository registrationRepository,
                          OutboxPublisher outboxPublisher,
                          GuestCapabilityTokenService tokenService,
                          SlotCacheVersionService slotCacheVersionService,
                          UserRepository userRepository,
                          EventTypeRepository eventTypeRepository,
                          TimeSource timeSource,
                          SessionLineageResolver lineageResolver,
                          RescheduleConflictService rescheduleConflictService) {
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.outboxPublisher = outboxPublisher;
        this.tokenService = tokenService;
        this.slotCacheVersionService = slotCacheVersionService;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.timeSource = timeSource;
        this.lineageResolver = lineageResolver;
        this.rescheduleConflictService = rescheduleConflictService;
    }

    /**
     * Finds an existing OPEN session for the given slot, or creates one.
     * The advisory lock on (hostId, startTime) must be acquired before this is called,
     * which happens automatically within joinSession's transaction.
     */
    @Transactional
    public JoinSessionResult joinSession(UUID hostId,
                                         UUID eventTypeId,
                                         Instant startTime,
                                         Instant endTime,
                                         int capacity,
                                         String guestEmail,
                                         String guestName,
                                         Duration holdDuration) {
        return joinSession(hostId, eventTypeId, startTime, endTime, capacity, guestEmail, guestName, null, null, null, holdDuration);
    }

    @Transactional
    public JoinSessionResult joinSession(UUID hostId,
                                         UUID eventTypeId,
                                         Instant startTime,
                                         Instant endTime,
                                         int capacity,
                                         String guestEmail,
                                         String guestName,
                                         String guestNotes,
                                         AuthProvider inviteeAuthProvider,
                                         String inviteeProviderUserId,
                                         Duration holdDuration) {
        // 1. Acquire advisory lock for this (host, slot) pair — serializes all concurrent joins.
        sessionRepository.acquireSlotLock(hostId.toString(), String.valueOf(startTime.getEpochSecond()));

        // 2. Find or create the session.
        EventSession session = findOrCreateSession(hostId, eventTypeId, startTime, endTime, capacity);

        // 3. Capacity and status checks (after lock — safe to read confirmed_count).
        if (session.getStatus() == SessionStatus.CANCELLED) {
            throw new CustomException(ErrorCode.SESSION_CANCELLED);
        }
        if (session.getConfirmedCount() >= session.getCapacity()) {
            throw new CustomException(ErrorCode.SESSION_CAPACITY_FULL);
        }

        // 4. Check for an existing non-cancelled registration for this guest.
        //    The advisory lock above serializes all joinSession calls for this slot,
        //    so this read-then-write is safe: no other thread is inside this block.
        //
        //    CONFIRMED                 → ALREADY_REGISTERED (seat permanently taken).
        //    PENDING + live hold       → REGISTRATION_HOLD_ACTIVE (hold still valid).
        //    PENDING + expired hold    → cancel inline so the partial unique index
        //                               slot is freed before we insert below.
        //
        //    expireRegistration is a CAS (version + status = PENDING guard). If the
        //    background reaper beats us and returns 0, the row is already CANCELLED
        //    and the index slot is freed regardless. Both outcomes allow the insert.
        Instant now = timeSource.now();
        registrationRepository.findActiveBySessionIdAndGuestEmail(session.getId(), guestEmail)
                .ifPresent(existing -> {
                    if (existing.getStatus() == RegistrationStatus.CONFIRMED) {
                        throw new CustomException(ErrorCode.ALREADY_REGISTERED);
                    }
                    // PENDING from here: check whether the hold is still live or expired.
                    boolean liveHold = existing.getExpiresAt() != null
                            && existing.getExpiresAt().isAfter(now);
                    if (liveHold) {
                        throw new RegistrationHoldActiveException(existing.getExpiresAt());
                    }
                    registrationRepository.expireRegistration(existing.getId(), existing.getVersion());
                });

        // 5. Insert PENDING registration.
        Instant expiresAt = holdDuration != null ? now.plus(holdDuration) : null;
        SessionRegistration registration;
        try {
            registration = registrationRepository.save(SessionRegistration.builder()
                    .sessionId(session.getId())
                    .hostId(hostId)
                    .guestEmail(guestEmail)
                    .guestName(guestName)
                    .guestNotes(guestNotes)
                    .inviteeAuthProvider(inviteeAuthProvider)
                    .inviteeProviderUserId(inviteeProviderUserId)
                    .status(RegistrationStatus.PENDING)
                    .expiresAt(expiresAt)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Guard against races outside the advisory lock scope (e.g. different
            // event type on the same session, or a reaper that re-inserted).
            throw new CustomException(ErrorCode.ALREADY_REGISTERED);
        }

        // 5. Publish outbox event.
        outboxPublisher.publish("Session", session.getId(), hostId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "REGISTRATION_HELD", 1,
                        new RegistrationHeldEvent(session.getId(), registration.getId(),
                                hostId, guestEmail, guestName, startTime, endTime)));

        slotCacheVersionService.bumpVersionAfterCommit(hostId);

        return new JoinSessionResult(session.getId(), registration.getId(), expiresAt);
    }

    /**
     * Confirms a PENDING registration.
     *
     * Order: reserve seat first (count++), then flip status to CONFIRMED.
     * If either step fails, the DB transaction rolls back both atomically — no
     * compensating write is needed.
     */
    @Transactional
    public ConfirmRegistrationResult confirmRegistration(UUID sessionId,
                                                          UUID registrationId,
                                                          UUID hostId) {
        // 1. Load registration — verify it exists and belongs to this host/session.
        SessionRegistration reg = registrationRepository.findByIdAndHostId(registrationId, hostId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Registration not found."));

        if (reg.getStatus() == RegistrationStatus.CONFIRMED) {
            // Idempotent — already confirmed; re-issue token.
            String token = tokenService.issueToken(registrationId, hostId,
                    BookingActionType.MANAGE_BOOKING, Duration.ofDays(365),
                    TokenCreatorType.SYSTEM);
            return new ConfirmRegistrationResult(sessionId, registrationId, hostId, token);
        }

        if (reg.getStatus() == RegistrationStatus.CANCELLED) {
            throw new CustomException(ErrorCode.REGISTRATION_EXPIRED,
                    "Registration has been cancelled.");
        }

        // Check expiry before acquiring any write locks.
        if (reg.getExpiresAt() != null && reg.getExpiresAt().isBefore(timeSource.now())) {
            throw new CustomException(ErrorCode.REGISTRATION_EXPIRED);
        }

        // 2. Acquire slot-level advisory lock to serialize all confirms for this slot.
        EventSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Session not found."));
        sessionRepository.acquireSlotLock(hostId.toString(),
                String.valueOf(session.getStartTime().getEpochSecond()));

        // 3. Reserve seat first: CAS confirmed_count++ on the session.
        //    This query also enforces confirmed_count < capacity.
        int sessionUpdated = sessionRepository.incrementConfirmedCount(sessionId);
        if (sessionUpdated == 0) {
            throw new CustomException(ErrorCode.SESSION_CAPACITY_FULL);
        }

        // 4. CAS registration PENDING → CONFIRMED.
        //    If this fails the transaction rolls back step 3 automatically.
        int regUpdated = registrationRepository.confirmRegistration(
                registrationId, reg.getVersion(), timeSource.now());
        if (regUpdated == 0) {
            // Expired or concurrently cancelled between our check and this update.
            throw new CustomException(ErrorCode.REGISTRATION_EXPIRED);
        }

        // 5. Issue capability token for future cancellation.
        String token = tokenService.issueToken(registrationId, hostId,
                BookingActionType.MANAGE_BOOKING, Duration.ofDays(365),
                TokenCreatorType.SYSTEM);

        // 6. Publish outbox event with confirmed attendee list embedded in payload.
        // Re-read session to get the updated calendar_sequence (incremented in step 3).
        EventSession updatedSession = sessionRepository.findById(sessionId)
                .orElse(session);
        List<SessionRegistration> confirmedAttendees =
                registrationRepository.findConfirmedBySessionId(sessionId);
        SessionContext ctx = resolveContext(hostId, session.getEventTypeId());
        outboxPublisher.publish("Session", sessionId, hostId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "REGISTRATION_CONFIRMED", 1,
                        new RegistrationConfirmedEvent(sessionId, registrationId, hostId,
                                session.getEventTypeId(),
                                ctx.hostUsername(), ctx.eventName(), ctx.eventSlug(),
                                reg.getGuestEmail(), reg.getGuestName(), reg.getGuestNotes(),
                                session.getStartTime(), session.getEndTime(),
                                (int) updatedSession.getCalendarSequence(),
                                updatedSession.getConfirmedCount(), updatedSession.getCapacity(),
                                token,
                                confirmedAttendees.stream()
                                        .map(r -> new AttendeeInfo(r.getGuestEmail(), r.getGuestName(), r.getGuestNotes()))
                                        .toList())));

        slotCacheVersionService.bumpVersionAfterCommit(hostId);

        return new ConfirmRegistrationResult(sessionId, registrationId, hostId, token);
    }

    /**
     * Moves a confirmed registration from one session of an event type to another
     * (guest self-reschedule).
     *
     * <p>Seat accounting reuses the same CAS primitives as join and cancel
     * ({@code incrementConfirmedCount} / {@code decrementConfirmedCount}), so capacity
     * enforcement and OPEN↔FULL flips behave identically on this path — there is no
     * second implementation of seat math to drift.
     *
     * <p>Both slot locks are taken in ascending start-time order. Two guests swapping
     * sessions in opposite directions would otherwise each hold the lock the other
     * needs; a consistent global order makes that deadlock unreachable.
     *
     * @param rawToken the guest's capability token, or null when a host performs the move
     */
    @Transactional
    public MoveRegistrationResult moveRegistration(UUID registrationId,
                                                    UUID hostId,
                                                    Instant targetStartTime,
                                                    String rawToken) {
        if (registrationId == null || hostId == null || targetStartTime == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "registrationId, hostId and startTime are required.");
        }
        if (rawToken != null && !tokenService.allows(registrationId, hostId, rawToken,
                BookingActionType.MANAGE_BOOKING)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        SessionRegistration reg = registrationRepository.findByIdAndHostId(registrationId, hostId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Registration not found."));
        if (reg.getStatus() != RegistrationStatus.CONFIRMED) {
            // PENDING holds reserve no seat and CANCELLED ones are terminal; neither
            // can be moved. Re-booking is the correct path for both.
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only confirmed registrations can be rescheduled.");
        }

        EventSession source = sessionRepository.findById(reg.getSessionId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Session not found."));
        Instant now = timeSource.now();
        if (!source.getStartTime().isAfter(now)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "This session has already started and can no longer be rescheduled.");
        }
        if (!targetStartTime.isAfter(now)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The new meeting time must be in the future.");
        }
        if (targetStartTime.equals(source.getStartTime())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The new meeting time matches the current one.");
        }

        EventType eventType = eventTypeRepository.findById(source.getEventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        Instant targetEndTime = targetStartTime.plus(
                Duration.between(source.getStartTime(), source.getEndTime()));

        // Lock both slots in a fixed order so opposing swaps cannot deadlock.
        Instant firstLock = source.getStartTime().isBefore(targetStartTime)
                ? source.getStartTime() : targetStartTime;
        Instant secondLock = firstLock.equals(source.getStartTime())
                ? targetStartTime : source.getStartTime();
        sessionRepository.acquireSlotLock(hostId.toString(), String.valueOf(firstLock.getEpochSecond()));
        sessionRepository.acquireSlotLock(hostId.toString(), String.valueOf(secondLock.getEpochSecond()));

        // Materializes the target if this is its first booking — same path, and the
        // same lineage stamping, as any other first registration.
        EventSession target = findOrCreateSession(hostId, source.getEventTypeId(),
                targetStartTime, targetEndTime, eventType.getCapacity());
        if (target.getId().equals(source.getId())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "The new meeting time matches the current one.");
        }
        if (target.getStatus() == SessionStatus.CANCELLED) {
            throw new CustomException(ErrorCode.SESSION_CANCELLED);
        }
        if (target.getStatus() == SessionStatus.COMPLETED) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "That session has already finished.");
        }

        // Take the seat before releasing the old one: if the target is full we fail
        // here with the guest still holding their original seat, rather than dropping
        // them from both.
        if (sessionRepository.incrementConfirmedCount(target.getId()) == 0) {
            throw new CustomException(ErrorCode.SESSION_CAPACITY_FULL);
        }
        if (registrationRepository.moveRegistration(
                registrationId, reg.getVersion(), source.getId(), target.getId()) == 0) {
            // Concurrently cancelled or moved; the transaction rolls back the seat we
            // just took on the target.
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Registration was modified concurrently.");
        }
        sessionRepository.decrementConfirmedCount(source.getId());

        EventSession updatedSource = sessionRepository.findById(source.getId()).orElse(source);
        EventSession updatedTarget = sessionRepository.findById(target.getId()).orElse(target);
        SessionContext ctx = resolveContext(hostId, source.getEventTypeId());

        outboxPublisher.publish("Session", target.getId(), hostId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "REGISTRATION_MOVED", 1,
                        new RegistrationMovedEvent(
                                source.getId(), target.getId(), registrationId, hostId,
                                source.getEventTypeId(),
                                ctx.hostUsername(), ctx.eventName(), ctx.eventSlug(),
                                reg.getGuestEmail(), reg.getGuestName(), reg.getGuestNotes(),
                                source.getStartTime(), source.getEndTime(),
                                updatedTarget.getStartTime(), updatedTarget.getEndTime(),
                                (int) updatedSource.getCalendarSequence(),
                                (int) updatedTarget.getCalendarSequence(),
                                updatedTarget.getConfirmedCount(), updatedTarget.getCapacity(),
                                attendeesOf(source.getId()), attendeesOf(target.getId()))));

        slotCacheVersionService.bumpVersionAfterCommit(hostId);

        return new MoveRegistrationResult(source.getId(), target.getId(), registrationId,
                updatedTarget.getStartTime(), updatedTarget.getEndTime());
    }

    private List<AttendeeInfo> attendeesOf(UUID sessionId) {
        return registrationRepository.findConfirmedBySessionId(sessionId).stream()
                .map(r -> new AttendeeInfo(r.getGuestEmail(), r.getGuestName(), r.getGuestNotes()))
                .toList();
    }

    /**
     * Cancels a single registration (attendee self-cancel or host removal).
     * If the registration was CONFIRMED, the session's confirmed_count is decremented.
     */
    @Transactional
    public void cancelRegistration(UUID sessionId,
                                   UUID registrationId,
                                   UUID hostId,
                                   String rawToken) {
        // 1. Validate capability token.
        if (rawToken != null && !tokenService.allows(registrationId, hostId, rawToken,
                BookingActionType.MANAGE_BOOKING)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        SessionRegistration reg = registrationRepository.findByIdAndHostId(registrationId, hostId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Registration not found."));

        if (reg.getStatus() == RegistrationStatus.CANCELLED) {
            return; // already cancelled — idempotent
        }

        boolean wasConfirmed = reg.getStatus() == RegistrationStatus.CONFIRMED;

        // 2. CAS cancel the registration.
        int updated = registrationRepository.cancelRegistration(registrationId, reg.getVersion());
        if (updated == 0) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Registration was modified concurrently.");
        }

        // 3. Decrement seat count if it was confirmed.
        if (wasConfirmed) {
            sessionRepository.decrementConfirmedCount(sessionId);
        }

        // 4. Revoke token.
        if (rawToken != null) {
            tokenService.revokeByRawToken(rawToken);
        }

        // 5. Publish outbox event.
        EventSession sessionForCancel = sessionRepository.findById(sessionId).orElse(null);
        SessionContext ctxCancel = sessionForCancel != null
                ? resolveContext(hostId, sessionForCancel.getEventTypeId())
                : new SessionContext(null, null, null);
        Instant startTimeForCancel = sessionForCancel != null ? sessionForCancel.getStartTime() : null;
        Instant endTimeForCancel = sessionForCancel != null ? sessionForCancel.getEndTime() : null;
        int calSeqForCancel = sessionForCancel != null ? (int) sessionForCancel.getCalendarSequence() : 0;
        int confirmedCountForCancel = sessionForCancel != null ? sessionForCancel.getConfirmedCount() : 0;
        int capacityForCancel = sessionForCancel != null ? sessionForCancel.getCapacity() : 0;
        UUID eventTypeIdForCancel = sessionForCancel != null ? sessionForCancel.getEventTypeId() : null;
        outboxPublisher.publish("Session", sessionId, hostId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "REGISTRATION_CANCELLED", 1,
                        new RegistrationCancelledEvent(sessionId, registrationId, hostId,
                                eventTypeIdForCancel,
                                ctxCancel.hostUsername(), ctxCancel.eventName(), ctxCancel.eventSlug(),
                                reg.getGuestEmail(), reg.getGuestName(), reg.getGuestNotes(), wasConfirmed,
                                startTimeForCancel, endTimeForCancel, calSeqForCancel,
                                confirmedCountForCancel, capacityForCancel)));

        slotCacheVersionService.bumpVersionAfterCommit(hostId);
    }

    /**
     * Host cancels the entire session. Atomically zeros confirmed_count and cancels
     * all active registrations in the same transaction.
     */
    @Transactional
    public void cancelSession(UUID sessionId, UUID hostId) {
        EventSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Session not found."));

        if (!session.getHostId().equals(hostId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        if (session.getStatus() == SessionStatus.CANCELLED
                || session.getStatus() == SessionStatus.COMPLETED) {
            return; // idempotent
        }

        // Load attendee list before cancellation for notification payload.
        List<SessionRegistration> activeRegs =
                registrationRepository.findActiveBySessionId(sessionId);

        // CAS cancel session + zero confirmed_count atomically.
        int updated = sessionRepository.cancelSession(sessionId, hostId);
        if (updated == 0) {
            log.warn("cancelSession noop sessionId={} — already terminal", sessionId);
            return;
        }

        EventSession updatedSession = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Session not found after cancellation."));

        // Bulk cancel all active registrations.
        registrationRepository.bulkCancelBySessionId(sessionId);

        // Publish outbox event with full attendee list embedded.
        SessionContext ctxSessionCancel = resolveContext(hostId, session.getEventTypeId());
        outboxPublisher.publish("Session", sessionId, hostId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "SESSION_CANCELLED", 1,
                        new SessionCancelledEvent(sessionId, hostId,
                                ctxSessionCancel.hostUsername(), ctxSessionCancel.eventName(), ctxSessionCancel.eventSlug(),
                                updatedSession.getStartTime(), updatedSession.getEndTime(),
                                (int) updatedSession.getCalendarSequence(),
                                activeRegs.stream()
                                        .map(r -> new AttendeeInfo(r.getGuestEmail(), r.getGuestName(), r.getGuestNotes()))
                                        .toList())));

        slotCacheVersionService.bumpVersionAfterCommit(hostId);
    }

    @Transactional
    public void rescheduleSession(UUID sessionId, UUID hostId, Instant newStartTime) {
        rescheduleSession(sessionId, hostId, newStartTime, false);
    }

    /**
     * @param acknowledgeExternalConflicts the host has seen and accepted busy time from their
     *        connected calendar. Never suppresses hard conflicts — those are not acknowledgeable.
     */
    @Transactional
    public void rescheduleSession(UUID sessionId, UUID hostId, Instant newStartTime,
                                  boolean acknowledgeExternalConflicts) {
        if (sessionId == null || hostId == null || newStartTime == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "sessionId, hostId and startTime are required.");
        }
        if (!newStartTime.isAfter(timeSource.now())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "The new meeting time must be in the future.");
        }

        EventSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found."));
        if (!hostId.equals(session.getHostId())) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        if (session.getStatus() == SessionStatus.CANCELLED || session.getStatus() == SessionStatus.COMPLETED) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only active meetings can be rescheduled.");
        }

        Duration duration = Duration.between(session.getStartTime(), session.getEndTime());
        if (duration.isZero() || duration.isNegative()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Meeting duration is invalid.");
        }
        Instant newEndTime = newStartTime.plus(duration);
        sessionRepository.acquireSlotLock(hostId.toString(), String.valueOf(newStartTime.getEpochSecond()));

        // Re-checked inside the transaction rather than trusting the preview endpoint: a
        // conflict can appear between the host seeing a clear dialog and confirming.
        RescheduleConflicts conflicts =
                rescheduleConflictService.check(hostId, newStartTime, newEndTime, sessionId);
        if (conflicts.hasHard()) {
            RescheduleConflicts.Conflict first = conflicts.hard().get(0);
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                    "This time overlaps \"" + first.title() + "\" on your calendar.");
        }
        if (conflicts.hasSoft() && !acknowledgeExternalConflicts) {
            RescheduleConflicts.Conflict first = conflicts.soft().get(0);
            throw new CustomException(ErrorCode.CONFIRMATION_REQUIRED,
                    "This time overlaps \"" + first.title() + "\" on your connected calendar.");
        }

        // event_sessions_unique_slot is UNIQUE (host_id, event_type_id, start_time) with no
        // status predicate, so even a CANCELLED session still owns its exact start time. The
        // overlap check above deliberately ignores cancelled sessions — correct for "is the host
        // busy?", but it would let this land as a raw constraint violation. Caught here so the
        // host gets an explanation instead of a 500.
        sessionRepository
                .findByHostIdAndEventTypeIdAndStartTime(hostId, session.getEventTypeId(), newStartTime)
                .filter(existing -> !sessionId.equals(existing.getId()))
                .ifPresent(existing -> {
                    throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                            "Another meeting for this event already starts at this exact time.");
                });

        List<SessionRegistration> attendees = registrationRepository.findActiveBySessionId(sessionId);
        // Sessions materialized before lineage tracking carry a null occurrence start. This
        // move is the last moment the original time is known, so seed it now rather than lose
        // it — without this, read paths cannot tell the rule's occurrence from where the
        // session was moved to, and re-offer the vacated slot as free. Seeding an absent
        // value, not rewriting a known one, so write-once still holds.
        Instant originalOccurrenceStart =
                session.getScheduledOccurrenceStart() == null ? session.getStartTime() : null;
        session.setStartTime(newStartTime);
        session.setEndTime(newEndTime);
        // The session no longer sits where its rule placed it. Lineage
        // (reservationWindowId, scheduledOccurrenceStart) is write-once and stays
        // pointing at the origin, so this move is recorded as a detachment rather
        // than by rewriting where the occurrence came from.
        if (session.getDetachedAt() == null) {
            session.setDetachedAt(timeSource.now());
            session.setDetachedReason(SessionDetachedReason.HOST_RESCHEDULED);
        }
        session.setCalendarSequence(session.getCalendarSequence() + 1);
        session.setVersion(session.getVersion() + 1);
        EventSession updatedSession = sessionRepository.save(session);
        if (originalOccurrenceStart != null) {
            sessionRepository.seedScheduledOccurrenceStart(sessionId, originalOccurrenceStart);
        }

        SessionContext context = resolveContext(hostId, session.getEventTypeId());
        outboxPublisher.publish("Session", sessionId, hostId,
                new OutboxPayloadEnvelope(UUID.randomUUID().toString(), "SESSION_RESCHEDULED", 1,
                        new SessionRescheduledEvent(sessionId, hostId,
                                context.hostUsername(), context.eventName(), context.eventSlug(),
                                updatedSession.getStartTime(), updatedSession.getEndTime(),
                                (int) updatedSession.getCalendarSequence(),
                                attendees.stream()
                                        .map(registration -> new AttendeeInfo(
                                                registration.getGuestEmail(),
                                                registration.getGuestName(),
                                                registration.getGuestNotes()))
                                        .toList())));
        slotCacheVersionService.bumpVersionAfterCommit(hostId);
    }

    /**
     * Expires a single PENDING registration. Called from RegistrationExpiryScheduler.
     * Does not decrement confirmed_count because PENDING registrations never increment it.
     */
    @Transactional
    public void expireRegistration(UUID registrationId, long version) {
        registrationRepository.expireRegistration(registrationId, version);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private record SessionContext(String hostUsername, String eventName, String eventSlug) {}

    private SessionContext resolveContext(UUID hostId, UUID eventTypeId) {
        String username = userRepository.findById(hostId).map(User::getUsername).orElse(null);
        EventType et = eventTypeRepository.findByIdAndUserId(eventTypeId, hostId).orElse(null);
        String name = et != null ? et.getName() : null;
        String slug = et != null ? et.getSlug() : null;
        return new SessionContext(username, name, slug);
    }

    /**
     * Finds the session for this slot, or materializes it.
     *
     * <p>This is the <b>only</b> place session lineage is written. Both
     * {@code reservationWindowId} and {@code scheduledOccurrenceStart} are write-once
     * (mapped {@code updatable = false}): they record where the occurrence came from,
     * not where it currently sits, so later reschedules must leave them alone.
     */
    private EventSession findOrCreateSession(UUID hostId, UUID eventTypeId,
                                              Instant startTime, Instant endTime,
                                              int capacity) {
        return sessionRepository
                .findByHostIdAndEventTypeIdAndStartTime(hostId, eventTypeId, startTime)
                .orElseGet(() -> {
                    try {
                        // Best-effort: a slot with no resolvable window (ad-hoc, or a
                        // window retired between listing and booking) still books fine
                        // with null lineage.
                        UUID windowId = lineageResolver.resolveWindow(hostId, eventTypeId, startTime)
                                .map(GroupEventReservationWindow::getId)
                                .orElse(null);
                        return sessionRepository.save(EventSession.builder()
                                .hostId(hostId)
                                .eventTypeId(eventTypeId)
                                .startTime(startTime)
                                .endTime(endTime)
                                .capacity(capacity)
                                .status(SessionStatus.OPEN)
                                .reservationWindowId(windowId)
                                // The rule placed the occurrence here; startTime may
                                // later move, this never does.
                                .scheduledOccurrenceStart(startTime)
                                .build());
                    } catch (DataIntegrityViolationException e) {
                        // Concurrent insert won the race; re-select the winner.
                        return sessionRepository
                                .findByHostIdAndEventTypeIdAndStartTime(hostId, eventTypeId, startTime)
                                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR,
                                        "Session creation race: re-select failed."));
                    }
                });
    }

    // ── Payload types (package-private for test visibility) ──────────────────

    record RegistrationHeldEvent(UUID sessionId, UUID registrationId, UUID hostId,
                                  String guestEmail, String guestName,
                                  Instant startTime, Instant endTime) {}

    record RegistrationConfirmedEvent(UUID sessionId, UUID registrationId, UUID hostId,
                                       UUID eventTypeId,
                                       String hostUsername, String eventName, String eventSlug,
                                       String guestEmail, String guestName, String guestNotes,
                                       Instant startTime, Instant endTime,
                                       int calendarSequence,
                                       int confirmedCount, int capacity,
                                       String capabilityToken,
                                       List<AttendeeInfo> allConfirmedAttendees) {}

    record RegistrationCancelledEvent(UUID sessionId, UUID registrationId, UUID hostId,
                                       UUID eventTypeId,
                                       String hostUsername, String eventName, String eventSlug,
                                       String guestEmail, String guestName, String guestNotes,
                                       boolean wasConfirmed,
                                       Instant startTime, Instant endTime,
                                       int calendarSequence,
                                       int confirmedCount, int capacity) {}

    record SessionCancelledEvent(UUID sessionId, UUID hostId,
                                  String hostUsername, String eventName, String eventSlug,
                                  Instant startTime, Instant endTime,
                                  int calendarSequence,
                                  List<AttendeeInfo> attendees) {}

    record SessionRescheduledEvent(UUID sessionId, UUID hostId,
                                   String hostUsername, String eventName, String eventSlug,
                                   Instant startTime, Instant endTime,
                                   int calendarSequence,
                                   List<AttendeeInfo> attendees) {}

    /**
     * A guest moved themselves from one session to another.
     *
     * <p>Carries both sides because two external calendar events change: the source
     * loses an attendee and the target gains one. Each side has its own
     * {@code calendarSequence} so iTIP updates are sequenced independently.
     */
    record RegistrationMovedEvent(UUID sourceSessionId, UUID targetSessionId,
                                   UUID registrationId, UUID hostId,
                                   UUID eventTypeId,
                                   String hostUsername, String eventName, String eventSlug,
                                   String guestEmail, String guestName, String guestNotes,
                                   Instant previousStartTime, Instant previousEndTime,
                                   Instant startTime, Instant endTime,
                                   int sourceCalendarSequence,
                                   int targetCalendarSequence,
                                   int confirmedCount, int capacity,
                                   List<AttendeeInfo> sourceAttendees,
                                   List<AttendeeInfo> targetAttendees) {}

    public record AttendeeInfo(String email, String name, String notes) {}
}
