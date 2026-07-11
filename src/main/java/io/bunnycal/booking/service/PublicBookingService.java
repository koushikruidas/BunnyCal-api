package io.bunnycal.booking.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.AuthIdentityRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.avatar.ProfileAvatarService;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.dto.AvailabilityStatus;
import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.service.ParticipantEligibilityService;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.booking.dto.PublicConfirmResponse;
import io.bunnycal.booking.dto.PublicBookingStatusResponse;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicEventInfoResponse;
import io.bunnycal.booking.dto.PublicHoldResponse;
import io.bunnycal.booking.dto.PublicManageBookingResponse;
import io.bunnycal.booking.dto.PublicRescheduleRequest;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.repository.CalendarEventMappingRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.calendar.service.CalendarService;
import io.bunnycal.calendar.service.CalendarBusyTimeService;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.availability.repository.EventTypeParticipantRepository;
import io.bunnycal.booking.dto.PublicParticipantInfo;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.logging.OpsLogSupport;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.domain.SessionRegistration;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.session.service.ConfirmRegistrationResult;
import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import io.bunnycal.booking.repository.CollectiveParticipantHoldRepository;
import io.bunnycal.sync.FencingTokenGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.bunnycal.embed.public_.EmbedBookingSupport;
import io.bunnycal.embed.public_.BookingQuestionAnswerRepository;
import io.bunnycal.form.dto.AnswerSnapshot;
import io.bunnycal.common.enums.AuthProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class PublicBookingService {
    private static final Logger log = LoggerFactory.getLogger(PublicBookingService.class);

    // Optional: only present when embed module is on the classpath (always in practice)
    @Autowired(required = false)
    private EmbedBookingSupport embedBookingSupport;

    private final PublicBookingTargetResolver publicBookingTargetResolver;
    private final SlotService slotService;
    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final CalendarBusyTimeService calendarBusyTimeService;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarService calendarService;
    private final CalendarEventMappingRepository calendarEventMappingRepository;
    private final FencingTokenGenerator fencingTokenGenerator;
    private final TimeConversionService timeConversionService;
    private final BookingLifecycleService bookingLifecycleService;
    private final GuestCapabilityTokenService guestCapabilityTokenService;
    private final SessionService sessionService;
    private final EventSessionRepository eventSessionRepository;
    private final SessionRegistrationRepository sessionRegistrationRepository;
    private final RoundRobinSlotTokenService roundRobinSlotTokenService;
    private final RoundRobinAssignmentService roundRobinAssignmentService;
    private final CollectiveSlotTokenService collectiveSlotTokenService;
    private final CollectiveAssignmentService collectiveAssignmentService;
    private final CollectiveParticipantHoldRepository collectiveParticipantHoldRepository;
    private final ParticipantEligibilityService participantEligibilityService;
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final UserRepository userRepository;
    private final ProfileAvatarService profileAvatarService;
    private final AuthIdentityRepository authIdentityRepository;
    private final BookingQuestionAnswerRepository bookingQuestionAnswerRepository;
    private final BookingSubmissionFormatter bookingSubmissionFormatter;
    private final EventTypeParticipantRepository eventTypeParticipantRepository;
    private final BookingEventTypeResolver bookingEventTypeResolver;
    private final io.bunnycal.billing.entitlement.EntitlementService entitlementService;
    private final Duration guestManageTokenTtl;
    private final Duration projectionFreshnessSla;
    private final MeterRegistry meterRegistry;

    @Autowired
    public PublicBookingService(PublicBookingTargetResolver publicBookingTargetResolver,
                                SlotService slotService,
                                BookingService bookingService,
                                BookingRepository bookingRepository,
                                CalendarBusyTimeService calendarBusyTimeService,
                                CalendarConnectionRepository calendarConnectionRepository,
                                CalendarService calendarService,
                                CalendarEventMappingRepository calendarEventMappingRepository,
                                FencingTokenGenerator fencingTokenGenerator,
                                TimeConversionService timeConversionService,
                                BookingLifecycleService bookingLifecycleService,
                                GuestCapabilityTokenService guestCapabilityTokenService,
                                SessionService sessionService,
                                EventSessionRepository eventSessionRepository,
                                SessionRegistrationRepository sessionRegistrationRepository,
                                RoundRobinSlotTokenService roundRobinSlotTokenService,
                                RoundRobinAssignmentService roundRobinAssignmentService,
                                CollectiveSlotTokenService collectiveSlotTokenService,
                                CollectiveAssignmentService collectiveAssignmentService,
                                CollectiveParticipantHoldRepository collectiveParticipantHoldRepository,
                                ParticipantEligibilityService participantEligibilityService,
                                BookingAssignmentRepository bookingAssignmentRepository,
                                UserRepository userRepository,
                                ProfileAvatarService profileAvatarService,
                                AuthIdentityRepository authIdentityRepository,
                                BookingQuestionAnswerRepository bookingQuestionAnswerRepository,
                                BookingSubmissionFormatter bookingSubmissionFormatter,
                                EventTypeParticipantRepository eventTypeParticipantRepository,
                                BookingEventTypeResolver bookingEventTypeResolver,
                                io.bunnycal.billing.entitlement.EntitlementService entitlementService,
                                MeterRegistry meterRegistry,
                                @Value("${booking.public.capability-token-ttl-days:14}") long capabilityTokenTtlDays,
                                // projection-first availability. If the connection's last
                                // successful sync is older than this, surface STALE_CALENDAR_DATA
                                // so the host UI can flag webhook lag instead of silently serving
                                // an out-of-date answer. 120s is one polling-fallback cycle + buffer.
                                @Value("${booking.public.projection-freshness-sla-seconds:120}")
                                long projectionFreshnessSlaSeconds) {
        this.publicBookingTargetResolver = publicBookingTargetResolver;
        this.slotService = slotService;
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
        this.calendarBusyTimeService = calendarBusyTimeService;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.calendarService = calendarService;
        this.calendarEventMappingRepository = calendarEventMappingRepository;
        this.fencingTokenGenerator = fencingTokenGenerator;
        this.timeConversionService = timeConversionService;
        this.bookingLifecycleService = bookingLifecycleService;
        this.guestCapabilityTokenService = guestCapabilityTokenService;
        this.sessionService = sessionService;
        this.eventSessionRepository = eventSessionRepository;
        this.sessionRegistrationRepository = sessionRegistrationRepository;
        this.roundRobinSlotTokenService = roundRobinSlotTokenService;
        this.roundRobinAssignmentService = roundRobinAssignmentService;
        this.collectiveSlotTokenService = collectiveSlotTokenService;
        this.collectiveAssignmentService = collectiveAssignmentService;
        this.collectiveParticipantHoldRepository = collectiveParticipantHoldRepository;
        this.participantEligibilityService = participantEligibilityService;
        this.bookingAssignmentRepository = bookingAssignmentRepository;
        this.userRepository = userRepository;
        this.profileAvatarService = profileAvatarService;
        this.authIdentityRepository = authIdentityRepository;
        this.bookingQuestionAnswerRepository = bookingQuestionAnswerRepository;
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
        this.eventTypeParticipantRepository = eventTypeParticipantRepository;
        this.bookingEventTypeResolver = bookingEventTypeResolver;
        this.entitlementService = entitlementService;
        this.meterRegistry = meterRegistry;
        this.guestManageTokenTtl = Duration.ofDays(Math.max(1L, capabilityTokenTtlDays));
        this.projectionFreshnessSla = Duration.ofSeconds(Math.max(1L, projectionFreshnessSlaSeconds));
    }

    public PublicBookingService(PublicBookingTargetResolver publicBookingTargetResolver,
                                SlotService slotService,
                                BookingService bookingService,
                                BookingRepository bookingRepository,
                                CalendarBusyTimeService calendarBusyTimeService,
                                CalendarConnectionRepository calendarConnectionRepository,
                                CalendarService calendarService,
                                CalendarEventMappingRepository calendarEventMappingRepository,
                                FencingTokenGenerator fencingTokenGenerator,
                                TimeConversionService timeConversionService,
                                BookingLifecycleService bookingLifecycleService,
                                GuestCapabilityTokenService guestCapabilityTokenService,
                                SessionService sessionService,
                                EventSessionRepository eventSessionRepository,
                                SessionRegistrationRepository sessionRegistrationRepository,
                                RoundRobinSlotTokenService roundRobinSlotTokenService,
                                RoundRobinAssignmentService roundRobinAssignmentService,
                                CollectiveSlotTokenService collectiveSlotTokenService,
                                CollectiveAssignmentService collectiveAssignmentService,
                                CollectiveParticipantHoldRepository collectiveParticipantHoldRepository,
                                ParticipantEligibilityService participantEligibilityService,
                                BookingAssignmentRepository bookingAssignmentRepository,
                                UserRepository userRepository,
                                ProfileAvatarService profileAvatarService,
                                EventTypeParticipantRepository eventTypeParticipantRepository,
                                BookingEventTypeResolver bookingEventTypeResolver,
                                io.bunnycal.billing.entitlement.EntitlementService entitlementService,
                                MeterRegistry meterRegistry,
                                long capabilityTokenTtlDays,
                                long projectionFreshnessSlaSeconds) {
        this(publicBookingTargetResolver, slotService, bookingService, bookingRepository, calendarBusyTimeService,
                calendarConnectionRepository, calendarService, calendarEventMappingRepository, fencingTokenGenerator,
                timeConversionService, bookingLifecycleService, guestCapabilityTokenService, sessionService,
                eventSessionRepository, sessionRegistrationRepository, roundRobinSlotTokenService, roundRobinAssignmentService,
                collectiveSlotTokenService, collectiveAssignmentService, collectiveParticipantHoldRepository,
                participantEligibilityService, bookingAssignmentRepository, userRepository, profileAvatarService, null, null,
                new BookingSubmissionFormatter(new ObjectMapper()), eventTypeParticipantRepository, bookingEventTypeResolver,
                entitlementService, meterRegistry, capabilityTokenTtlDays, projectionFreshnessSlaSeconds);
    }

    @Transactional(readOnly = true)
    public PublicEventInfoResponse eventInfo(String username, String eventTypeSlug) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        EventType eventType = bookingEventTypeResolver.requireByEventTypeId(target.eventTypeId());
        List<PublicParticipantInfo> participants = List.of();
        if (eventType.getKind() == EventKind.COLLECTIVE) {
            participants = eventTypeParticipantRepository
                    .findByEventTypeIdOrderByDisplayOrderAscCreatedAtAsc(eventType.getId())
                    .stream()
                    .map(p -> {
                        var user = userRepository.findById(p.getUserId()).orElse(null);
                        String name = user != null ? (user.getName() != null ? user.getName() : user.getEmail()) : null;
                        String avatarUrl = user != null ? profileAvatarService.resolveProfileImageUrl(user) : null;
                        return new PublicParticipantInfo(name, avatarUrl);
                    })
                    .filter(p -> p.name() != null)
                    .toList();
        }
        // Which weekdays the host works, so the calendar can grey out the rest instead of assuming
        // Mon–Fri.
        List<String> availableDays = slotService.availableDaysFor(target.userId()).stream()
                .map(Enum::name)
                .toList();

        return new PublicEventInfoResponse(
                target.eventName(),
                target.duration().toMinutes(),
                target.timezone(),
                target.hostName(),
                target.hostUsername(),
                target.eventDescription(),
                target.eventLocation(),
                target.hostAvatarUrl(),
                eventType.getKind(),
                eventType.isPublished(),
                participants,
                availableDays
        );
    }

    @Transactional(readOnly = true)
    public SlotResponse availability(String username, String eventTypeSlug, LocalDate date) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);

        // Unpublished event types: page is reachable but slots are empty.
        EventType currentEventType = bookingEventTypeResolver.requireByEventTypeId(target.eventTypeId());
        if (!currentEventType.isPublished()) {
            SlotResponse empty = slotService.getSlots(new SlotRequest(target.userId(), target.eventTypeId(), date));
            return new SlotResponse(empty.userId(), empty.eventTypeId(), empty.date(), empty.timezone(),
                    empty.version(), empty.generatedAt(), false, List.of(), AvailabilityStatus.NO_SLOTS_AVAILABLE);
        }
        // Use the oldest active connection as the staleness indicator (same as sync routing).
        java.util.Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(target.userId(), CalendarConnectionStatus.ACTIVE)
                        .stream().findFirst();
        if (connection.isEmpty()) {
            SlotResponse base = slotService.getSlots(new SlotRequest(target.userId(), target.eventTypeId(), date));
            return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                    base.version(), base.generatedAt(), true, base.slots(), AvailabilityStatus.CALENDAR_NOT_CONNECTED);
        }
        CalendarConnectionStatus status = connection.get().getStatus();

        SlotResponse base = slotService.getSlots(new SlotRequest(target.userId(), target.eventTypeId(), date));

        // P3: projection-first. The DB-side calendar_events projection is the system
        // of record for busy time. We no longer hit Google live on the read path —
        // it added 200–500ms of provider latency on every public page load and
        // created a TOCTOU race that conflicted with the eventually-consistent
        // webhook ingestion model. Instead we classify the response by projection
        // freshness so the UI can flag a degraded view explicitly.
        Instant lastSyncedAt = connection.map(CalendarConnection::getLastSyncedAt).orElse(null);
        boolean watchLive = connection.map(this::hasLiveWatchChannel).orElse(false);
        boolean stale = isProjectionStale(connection.orElse(null));
        AvailabilityStatus responseStatus;
        if (base.status() == AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS) {
            // Preserve multi-participant readiness failure regardless of projection staleness.
            responseStatus = AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS;
        } else if (stale) {
            responseStatus = AvailabilityStatus.STALE_CALENDAR_DATA;
        } else if (base.slots().isEmpty()) {
            responseStatus = AvailabilityStatus.NO_SLOTS_AVAILABLE;
        } else {
            responseStatus = AvailabilityStatus.AVAILABLE;
        }
        log.info("availability_decision userId={} eventTypeId={} date={} connectionStatus={} decision={} version={} baseSlots={} lastSyncedAt={} watchLive={} stale={}",
                target.userId(), target.eventTypeId(), date, status == null ? "MISSING" : status,
                stale ? "PROJECTION_STALE" : "PROJECTION_FRESH",
                base.version(), base.slots().size(), lastSyncedAt, watchLive, stale);
        return new SlotResponse(base.userId(), base.eventTypeId(), base.date(), base.timezone(),
                base.version(), base.generatedAt(), stale || base.degraded(), base.slots(), responseStatus);
    }

    /**
     * Is the DB-side calendar projection too old to trust?
     *
     * <p>{@code lastSyncedAt} only advances when events actually ingest, so it answers "when did
     * the calendar last change?", not "are we still hearing about changes?". Those diverge for a
     * webhook-backed connection: the sync scheduler deliberately stops polling a connection whose
     * watch channel is live (see {@code CalendarConnectionRepository#findDueForSyncBatchGated}),
     * so an idle calendar ingests nothing, {@code lastSyncedAt} freezes, and a purely time-based
     * check declares a perfectly healthy connection stale — the steady state for most hosts, whose
     * calendars are quiet for long stretches.
     *
     * <p>So ask the question the scheduler is already asking. While the watch channel is live and
     * unexpired, changes arrive in real time and the projection is fresh no matter how long it has
     * been quiet. The time-based SLA still applies to connections with no channel or an expired one,
     * which really are only as fresh as their last poll.
     *
     * <p>A watch that stops delivering silently is caught by the backstop: the scheduler polls even
     * webhook-fresh connections once {@code lastSyncedAt} passes {@code calendar.sync.webhook-fresh-backstop},
     * and a failing poll flips the connection to FAILED/ERROR, which is stale here.
     */
    private boolean isProjectionStale(CalendarConnection connection) {
        if (connection == null) {
            return true;
        }
        CalendarConnectionStatus status = connection.getStatus();
        if (status == CalendarConnectionStatus.FAILED
                || status == CalendarConnectionStatus.ERROR
                || status == CalendarConnectionStatus.REVOKED) {
            // REVOKED means the user intentionally disconnected or the token was invalidated;
            // the projection is effectively frozen and must not be treated as fresh regardless
            // of lastSyncedAt.
            return true;
        }
        Instant lastSyncedAt = connection.getLastSyncedAt();
        if (lastSyncedAt == null) {
            // Never synced — nothing has been ingested yet, so there is no projection to trust,
            // whether or not a watch channel exists.
            return true;
        }
        if (hasLiveWatchChannel(connection)) {
            return false;
        }
        return Duration.between(lastSyncedAt, Instant.now()).compareTo(projectionFreshnessSla) > 0;
    }

    /** A registered watch channel that has not expired — i.e. changes should still be delivered. */
    private boolean hasLiveWatchChannel(CalendarConnection connection) {
        String channelId = connection.getWebhookChannelId();
        Instant expiresAt = connection.getWebhookChannelExpiresAt();
        return channelId != null && !channelId.isBlank()
                && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    @Transactional
    public PublicHoldResponse hold(String username, String eventTypeSlug, PublicBookRequest request) {
        return hold(username, eventTypeSlug, request, null);
    }

    @Transactional
    public PublicHoldResponse hold(String username, String eventTypeSlug, PublicBookRequest request, UUID authenticatedInviteeId) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }

        InviteeAuthContext inviteeAuth = resolveInviteeAuthContext(authenticatedInviteeId);

        // Step 1 & 2: validate embed token + answers BEFORE any booking state mutation
        List<AnswerSnapshot> answerSnapshots = List.of();
        if (request.embedToken() != null && !request.embedToken().isBlank() && embedBookingSupport != null) {
            answerSnapshots = embedBookingSupport.validateEmbedRequest(request.embedToken(), request.answers());
        }

        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);

        // Gate: unpublished event types do not accept new holds.
        // The public page remains reachable but no new slots/holds/bookings are allowed.
        requirePublished(target.eventTypeId());

        // Gate: a premium event type whose owner is no longer entitled is inactive — it cannot
        // accept NEW bookings (Spec Ch5 §11/§14-16). Existing bookings are untouched (this only
        // runs on the create/hold path). Neutral rejection, no billing detail leaked (Principle 9).
        requireOwnerEntitledForBooking(target);

        PublicHoldResponse response;
        if (target.kind() == EventKind.GROUP) {
            response = holdGroupRegistration(target, request, inviteeAuth);
        } else if (target.kind() == EventKind.ROUND_ROBIN) {
            response = holdRoundRobinBooking(target, request, inviteeAuth);
        } else if (target.kind() == EventKind.COLLECTIVE) {
            response = holdCollectiveBooking(target, request, inviteeAuth);
        } else {
            response = holdOneOnOneBooking(target, request, inviteeAuth);
        }

        // Step 4: persist answers in the same transaction (booking just created in step 3)
        if (!answerSnapshots.isEmpty() && response.bookingId() != null && embedBookingSupport != null) {
            embedBookingSupport.persistAnswers(response.bookingId(), target.userId(), answerSnapshots);
        }

        return response;
    }

    private void requirePublished(UUID eventTypeId) {
        EventType et = bookingEventTypeResolver.requireByEventTypeId(eventTypeId);
        if (!et.isPublished()) {
            throw new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED);
        }
    }

    /**
     * Rejects a new booking on a premium event type whose owner is not currently entitled.
     * Reuses the neutral {@code EVENT_TYPE_NOT_PUBLISHED} response so a public visitor sees the
     * same "not accepting bookings" message regardless of why (no billing/subscription leak,
     * Principle 9). One-to-One is always allowed and is skipped.
     */
    private void requireOwnerEntitledForBooking(PublicBookingTargetResolver.ResolvedTarget target) {
        var requiredFeature = io.bunnycal.availability.service.EventKindEntitlements.requiredFeature(target.kind());
        if (requiredFeature != null
                && !entitlementService.resolve(target.userId()).has(requiredFeature)) {
            throw new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED);
        }
    }

    private PublicHoldResponse holdOneOnOneBooking(PublicBookingTargetResolver.ResolvedTarget target,
                                                   PublicBookRequest request,
                                                   InviteeAuthContext inviteeAuth) {
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        String guestNotes = normalizeNotes(request.notes());
        var booking = guestNotes == null && inviteeAuth.provider() == null && inviteeAuth.providerUserId() == null
                ? bookingService.createHeldBooking(
                        target.userId(),
                        target.eventTypeId(),
                        start,
                        end,
                        target.holdDuration(),
                        normalizeGuestEmail(request.guestEmail()),
                        normalizeGuestName(request.guestName()))
                : bookingService.createHeldBooking(
                        target.userId(),
                        target.eventTypeId(),
                        start,
                        end,
                        target.holdDuration(),
                        normalizeGuestEmail(request.guestEmail()),
                        normalizeGuestName(request.guestName()),
                        guestNotes,
                        inviteeAuth.provider(),
                        inviteeAuth.providerUserId()
                );
        OpsLoggers.BOOKING.info("booking_hold_created bookingId={} hostId={} eventTypeId={} startTimeUtc={} endTimeUtc={} guestEmail={} guestNamePresent={}",
                booking.getId(),
                target.userId(),
                target.eventTypeId(),
                booking.getStartTime(),
                booking.getEndTime(),
                OpsLogSupport.maskEmail(booking.getGuestEmail()),
                booking.getGuestName() != null && !booking.getGuestName().isBlank());

        var state = bookingRepository.findStateByIdAndHostAndEventType(booking.getId(), target.userId(), target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "Booking state missing."));
        return PublicHoldResponse.oneOnOne(
                booking.getId(),
                state.getExpiresAt(),
                booking.getStartTime(),
                booking.getEndTime()
        );
    }

    private PublicHoldResponse holdRoundRobinBooking(PublicBookingTargetResolver.ResolvedTarget target,
                                                     PublicBookRequest request,
                                                     InviteeAuthContext inviteeAuth) {
        if (request.slotToken() == null || request.slotToken().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "slotToken is required for round robin booking.");
        }
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        RoundRobinSlotTokenService.DecodedSlotToken token = roundRobinSlotTokenService.verify(request.slotToken());
        if (!target.userId().equals(token.ownerUserId())
                || !target.eventTypeId().equals(token.eventTypeId())
                || !start.equals(token.start())
                || !end.equals(token.end())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Round robin slot token does not match the requested slot.");
        }

        EventType eventType = bookingEventTypeResolver.requireByEventTypeId(target.eventTypeId());
        String guestNotes = normalizeNotes(request.notes());
        RoundRobinAssignmentService.AssignedRoundRobinBooking assigned =
                guestNotes == null && inviteeAuth.provider() == null && inviteeAuth.providerUserId() == null
                        ? roundRobinAssignmentService.assignAndCreateHeldBooking(
                                eventType,
                                start,
                                end,
                                token.candidateParticipantIds(),
                                target.holdDuration(),
                                normalizeGuestEmail(request.guestEmail()),
                                normalizeGuestName(request.guestName()))
                        : roundRobinAssignmentService.assignAndCreateHeldBooking(
                                eventType,
                                start,
                                end,
                                token.candidateParticipantIds(),
                                target.holdDuration(),
                                normalizeGuestEmail(request.guestEmail()),
                                normalizeGuestName(request.guestName()),
                                guestNotes,
                                inviteeAuth.provider(),
                                inviteeAuth.providerUserId());

        var state = bookingRepository.findStateByIdAndEventTypeId(assigned.booking().getId(), target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "Booking state missing."));
        return PublicHoldResponse.oneOnOne(
                assigned.booking().getId(),
                state.getExpiresAt(),
                assigned.booking().getStartTime(),
                assigned.booking().getEndTime()
        );
    }

    private PublicHoldResponse holdCollectiveBooking(PublicBookingTargetResolver.ResolvedTarget target,
                                                     PublicBookRequest request,
                                                     InviteeAuthContext inviteeAuth) {
        if (request.slotToken() == null || request.slotToken().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "slotToken is required for collective booking.");
        }
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        CollectiveSlotTokenService.DecodedCollectiveToken token = collectiveSlotTokenService.verify(request.slotToken());
        if (!target.userId().equals(token.ownerUserId())
                || !target.eventTypeId().equals(token.eventTypeId())
                || !start.equals(token.start())
                || !end.equals(token.end())) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Collective slot token does not match the requested slot.");
        }

        EventType eventType = bookingEventTypeResolver.requireByEventTypeId(target.eventTypeId());
        // Fetch the current roster and validate it matches the token's roster hash.
        // This is the primary guard against stale tokens after a participant change.
        List<UUID> currentParticipantIds =
                collectiveAssignmentService.currentParticipantIds(eventType);
        collectiveSlotTokenService.validateRosterMatch(token, currentParticipantIds);

        CollectiveAssignmentService.CreatedCollectiveBooking result =
                collectiveAssignmentService.createHeldBooking(
                        eventType,
                        target.userId(),
                        start,
                        end,
                        currentParticipantIds,
                        target.holdDuration(),
                        normalizeGuestEmail(request.guestEmail()),
                        normalizeGuestName(request.guestName()),
                        normalizeNotes(request.notes()),
                        inviteeAuth.provider(),
                        inviteeAuth.providerUserId());

        var state = bookingRepository.findStateByIdAndEventTypeId(result.booking().getId(), target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "Booking state missing."));

        OpsLoggers.BOOKING.info("collective_hold_created bookingId={} ownerUserId={} eventTypeId={} participantCount={} start={} end={}",
                result.booking().getId(), target.userId(), target.eventTypeId(),
                result.participantIds().size(), start, end);

        return PublicHoldResponse.oneOnOne(
                result.booking().getId(),
                state.getExpiresAt(),
                result.booking().getStartTime(),
                result.booking().getEndTime());
    }

    private PublicHoldResponse holdGroupRegistration(PublicBookingTargetResolver.ResolvedTarget target,
                                                     PublicBookRequest request,
                                                     InviteeAuthContext inviteeAuth) {
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        JoinSessionResult result = sessionService.joinSession(
                target.userId(),
                target.eventTypeId(),
                start,
                end,
                target.capacity(),
                normalizeGuestEmail(request.guestEmail()),
                normalizeGuestName(request.guestName()),
                normalizeNotes(request.notes()),
                inviteeAuth.provider(),
                inviteeAuth.providerUserId(),
                target.holdDuration()
        );
        OpsLoggers.BOOKING.info("group_registration_held registrationId={} sessionId={} hostId={} eventTypeId={} startTimeUtc={} endTimeUtc={} guestEmail={}",
                result.registrationId(),
                result.sessionId(),
                target.userId(),
                target.eventTypeId(),
                start,
                end,
                OpsLogSupport.maskEmail(request.guestEmail()));

        return new PublicHoldResponse(
                result.registrationId(),
                result.expiresAt(),
                start,
                end,
                result.sessionId()
        );
    }

    @Transactional
    public PublicConfirmResponse confirm(String username, String eventTypeSlug, UUID bookingId) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        BookingLogContext context = buildConfirmContext(target, bookingId);
        long startedAtNanos = System.nanoTime();
        try {
            PublicConfirmResponse response;
            if (target.kind() == EventKind.GROUP) {
                response = confirmGroupRegistration(target, bookingId);
            } else if (target.kind() == EventKind.ROUND_ROBIN) {
                response = confirmRoundRobinBooking(target, bookingId);
            } else if (target.kind() == EventKind.COLLECTIVE) {
                response = confirmCollectiveBooking(target, bookingId);
            } else {
                response = confirmOneOnOneBooking(target, bookingId);
            }
            emitConfirmSuccess(target, bookingId, context, startedAtNanos);
            return response;
        } catch (CustomException ex) {
            emitConfirmRejected(target, bookingId, context, ex, startedAtNanos);
            throw ex;
        } catch (RuntimeException ex) {
            emitConfirmFailed(target, bookingId, context, ex, startedAtNanos);
            throw ex;
        }
    }

    private PublicConfirmResponse confirmRoundRobinBooking(PublicBookingTargetResolver.ResolvedTarget target,
                                                           UUID bookingId) {
        var booking = bookingRepository.findAnyByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        var state = bookingRepository.findStateByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = booking.getStartTime();
        Instant end = booking.getEndTime();

        // Re-validate that the assigned participant is still eligible. They may have gone
        // INACTIVE or deleted their availability rules between the hold and confirm steps.
        UUID assignedParticipantId = booking.getHostId();
        var eligibility = participantEligibilityService.checkForRoundRobin(assignedParticipantId);
        if (!eligibility.eligible()) {
            log.info("rr_confirm_rejected_participant_no_longer_eligible bookingId={} participantId={} reason={}",
                    bookingId, assignedParticipantId, eligibility.reason());
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                    "The assigned participant is no longer available. Please select a new time slot.");
        }

        // Check for competing CONFIRMED bookings on the assigned participant.
        long conflicts = bookingRepository.countConflictsExcludingBooking(assignedParticipantId, bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        // Check the assigned participant's calendar busy blocks.
        String actualTimezone = resolveBookingHostTimezone(booking);
        boolean hasProjectionBusy = hasProjectionBusyConflict(assignedParticipantId, actualTimezone, start, end);
        if (hasProjectionBusy) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.confirmHeldBooking(bookingId);
        String manageToken = guestCapabilityTokenService.issueToken(
                bookingId,
                assignedParticipantId,
                BookingActionType.MANAGE_BOOKING,
                guestManageTokenTtl,
                TokenCreatorType.SYSTEM
        );
        return PublicConfirmResponse.oneOnOne(bookingId, "SYNCING", manageToken);
    }

    private PublicConfirmResponse confirmOneOnOneBooking(PublicBookingTargetResolver.ResolvedTarget target,
                                                         UUID bookingId) {
        var booking = bookingRepository.findAnyByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        var state = bookingRepository.findStateByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = booking.getStartTime();
        Instant end = booking.getEndTime();

        long conflicts = bookingRepository.countConflictsExcludingBooking(booking.getHostId(), bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        String actualTimezone = resolveBookingHostTimezone(booking);
        boolean hasProjectionBusy = hasProjectionBusyConflict(booking.getHostId(), actualTimezone, start, end);
        if (hasProjectionBusy) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.confirmHeldBooking(bookingId);
        String manageToken = guestCapabilityTokenService.issueToken(
                bookingId,
                booking.getHostId(),
                BookingActionType.MANAGE_BOOKING,
                guestManageTokenTtl,
                TokenCreatorType.SYSTEM
        );
        return PublicConfirmResponse.oneOnOne(bookingId, "SYNCING", manageToken);
    }

    private PublicConfirmResponse confirmCollectiveBooking(PublicBookingTargetResolver.ResolvedTarget target,
                                                           UUID bookingId) {
        var booking = bookingRepository.findAnyByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        var state = bookingRepository.findStateByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = booking.getStartTime();
        Instant end = booking.getEndTime();

        // Re-validate every assigned participant: eligibility + calendar + writeback + no conflicts.
        List<BookingAssignment> assignments = bookingAssignmentRepository.findAllByBookingId(bookingId);
        for (BookingAssignment assignment : assignments) {
            UUID participantId = assignment.getParticipantUserId();

            var eligibility = participantEligibilityService.checkForRoundRobin(participantId);
            if (!eligibility.eligible()) {
                log.info("collective_confirm_rejected_participant_ineligible bookingId={} participantId={} reason={}",
                        bookingId, participantId, eligibility.reason());
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "A participant is no longer available. Please select a new time slot.");
            }

            long conflicts = bookingRepository.countConflictsExcludingBooking(participantId, bookingId, start, end);
            if (conflicts > 0) {
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available.");
            }

            String tz = resolveUserTimezone(participantId);
            if (hasProjectionBusyConflict(participantId, tz, start, end)) {
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available.");
            }
        }

        bookingService.confirmHeldBooking(bookingId);
        // Participant holds are released: slot is now committed, no longer needs conflict protection.
        collectiveParticipantHoldRepository.releaseByBookingId(bookingId);

        String manageToken = guestCapabilityTokenService.issueToken(
                bookingId,
                booking.getHostId(),
                BookingActionType.MANAGE_BOOKING,
                guestManageTokenTtl,
                TokenCreatorType.SYSTEM);

        log.info("collective_booking_confirmed bookingId={} eventTypeId={} participantCount={}",
                bookingId, target.eventTypeId(), assignments.size());
        return PublicConfirmResponse.oneOnOne(bookingId, "SYNCING", manageToken);
    }

    private PublicConfirmResponse confirmGroupRegistration(PublicBookingTargetResolver.ResolvedTarget target,
                                                           UUID registrationId) {
        // GROUP confirm bypasses calendar conflict check — session occupancy is the
        // authority; the slot was validated when the attendee joined.
        ConfirmRegistrationResult result = sessionService.confirmRegistration(
                resolveSessionId(registrationId, target.userId()),
                registrationId,
                target.userId()
        );
        log.info("group_registration_confirmed registrationId={} sessionId={} hostId={}",
                registrationId, result.sessionId(), target.userId());
        return new PublicConfirmResponse(registrationId, "SYNCING", result.capabilityToken(), result.sessionId());
    }

    private UUID resolveSessionId(UUID registrationId, UUID hostId) {
        return sessionRegistrationRepository.findByIdAndHostId(registrationId, hostId)
                .map(r -> r.getSessionId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Registration not found."));
    }

    @Transactional(readOnly = true)
    public PublicManageBookingResponse manageView(String username,
                                                  String eventTypeSlug,
                                                  UUID bookingId,
                                                  String guestCapabilityToken) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);
        Booking booking = bookingLifecycleService.authorizeGuestManageView(bookingId, target.eventTypeId(), guestCapabilityToken);

        var row = bookingRepository.findManageRowByEventType(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        User assignedHost = userRepository.findById(booking.getHostId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Host not found."));

        String eventTitle = row.getEventTypeName() != null ? row.getEventTypeName() : target.eventName();
        String conferenceUrl = row.getConferenceUrl();
        String provider = row.getProvider();
        io.bunnycal.booking.dto.ConferenceDetailsResponse conferenceDetails = conferenceUrl == null || conferenceUrl.isBlank()
                ? io.bunnycal.booking.dto.ConferenceDetailsResponse.none()
                : new io.bunnycal.booking.dto.ConferenceDetailsResponse(
                        provider == null ? "UNKNOWN" : provider.toUpperCase(java.util.Locale.ROOT),
                        conferenceUrl, null, null, null, "projection");
        var questionnaireResponses = bookingSubmissionFormatter.toResponses(
                bookingQuestionAnswerRepository == null
                        ? List.of()
                        : bookingQuestionAnswerRepository.findByBookingIdAndHostId(bookingId, booking.getHostId()));
        return new PublicManageBookingResponse(
                row.getBookingId(),
                eventTitle,
                target.duration().toMinutes(),
                row.getStartTime(),
                row.getEndTime(),
                assignedHost.getName(),
                assignedHost.getUsername(),
                profileAvatarService.resolveProfileImageUrl(assignedHost),
                row.getGuestName(),
                row.getGuestEmail(),
                booking.getGuestNotes(),
                questionnaireResponses,
                conferenceDetails,
                row.getBookingStatus(),
                row.getExternalLifecycleState(),
                row.getExternalLifecycleReason(),
                assignedHost.getTimezone()
        );
    }

    @Transactional
    public PublicBookingStatusResponse cancel(String username, String eventTypeSlug, UUID bookingId, String guestCapabilityToken) {
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);

        if (target.kind() == EventKind.GROUP) {
            return cancelGroupRegistration(target, bookingId, guestCapabilityToken);
        }
        var booking = bookingLifecycleService.cancelAsGuest(bookingId, target.eventTypeId(), guestCapabilityToken);
        if (target.kind() == EventKind.COLLECTIVE) {
            collectiveParticipantHoldRepository.releaseByBookingId(bookingId);
        }
        return new PublicBookingStatusResponse(
                bookingId,
                "CANCELLED",
                booking.getStartTime(),
                booking.getEndTime(),
                null
        );
    }

    private PublicBookingStatusResponse cancelGroupRegistration(PublicBookingTargetResolver.ResolvedTarget target,
                                                                 UUID registrationId,
                                                                 String guestCapabilityToken) {
        UUID sessionId = resolveSessionId(registrationId, target.userId());
        sessionService.cancelRegistration(sessionId, registrationId, target.userId(), guestCapabilityToken);
        log.info("group_registration_cancelled registrationId={} sessionId={} hostId={}",
                registrationId, sessionId, target.userId());
        // Return a minimal status response; start/end times are on the session, not the registration.
        return new PublicBookingStatusResponse(registrationId, "CANCELLED", null, null, null);
    }

    @Transactional
    public PublicBookingStatusResponse reschedule(String username,
                                                  String eventTypeSlug,
                                                  UUID bookingId,
                                                  PublicRescheduleRequest request,
                                                  String guestCapabilityToken) {
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        PublicBookingTargetResolver.ResolvedTarget target = publicBookingTargetResolver.resolve(username, eventTypeSlug);

        if (target.kind() == EventKind.GROUP) {
            throw new CustomException(ErrorCode.GROUP_ATTENDEE_RESCHEDULE_NOT_SUPPORTED);
        }
        if (target.kind() == EventKind.COLLECTIVE) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Rescheduling is not supported for collective bookings.");
        }
        Booking booking = bookingLifecycleService.authorizeGuestReschedule(bookingId, target.eventTypeId(), guestCapabilityToken);

        var state = bookingRepository.findStateByIdAndEventTypeId(bookingId, target.eventTypeId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        Instant start = request.startTime();
        Instant end = start.plus(target.duration());

        if (target.kind() == EventKind.ROUND_ROBIN) {
            // For RR, re-validate that the assigned participant is still eligible and that
            // the new time falls within their availability window.
            UUID assignedParticipantId = booking.getHostId();
            var eligibility = participantEligibilityService.checkForRoundRobin(assignedParticipantId);
            if (!eligibility.eligible()) {
                log.info("rr_reschedule_rejected_participant_no_longer_eligible bookingId={} participantId={} reason={}",
                        bookingId, assignedParticipantId, eligibility.reason());
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "The assigned participant is no longer available. Please select a new time slot.");
            }

            // Verify the requested new slot actually falls within the participant's availability.
            EventType eventType = bookingEventTypeResolver.requireByEventTypeId(target.eventTypeId());
            LocalDate date = start.atZone(ZoneId.of(resolveBookingHostTimezone(booking))).toLocalDate();
            boolean participantFree = roundRobinAssignmentService
                    .candidateParticipantsForSlot(eventType, start, end, List.of(assignedParticipantId), Instant.now())
                    .contains(assignedParticipantId);
            if (!participantFree) {
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "The assigned participant is not available at the requested time.");
            }
        }

        long conflicts = bookingRepository.countConflictsExcludingBooking(booking.getHostId(), bookingId, start, end);
        if (conflicts > 0) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }
        if (hasProjectionBusyConflict(booking.getHostId(), resolveBookingHostTimezone(booking), start, end)) {
            throw new CustomException(ErrorCode.SLOT_UNAVAILABLE, "This time slot is no longer available");
        }

        bookingService.updateBooking(bookingId, booking.getHostId(), start, end, state.getVersion());
        return new PublicBookingStatusResponse(
                bookingId,
                state.getStatus(),
                start,
                end,
                state.getExpiresAt()
        );
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }

    private boolean hasProjectionBusyConflict(UUID userId, String timezone, Instant start, Instant end) {
        ZoneId zoneId = timeConversionService.resolveZone(timezone);
        LocalDate date = start.atZone(zoneId).toLocalDate();
        return calendarBusyTimeService.busyIntervalsForDate(userId, date, zoneId, List.of()).stream()
                .anyMatch(interval -> overlaps(
                        start,
                        end,
                        interval.start().toInstant(),
                        interval.end().toInstant()));
    }

    private static String normalizeGuestEmail(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v.toLowerCase();
    }

    private static String normalizeGuestName(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normalizeNotes(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private InviteeAuthContext resolveInviteeAuthContext(UUID authenticatedInviteeId) {
        if (authenticatedInviteeId == null || authIdentityRepository == null) {
            return InviteeAuthContext.NONE;
        }
        return authIdentityRepository.findByUserIdOrderByCreatedAtDesc(authenticatedInviteeId).stream()
                .findFirst()
                .map(identity -> new InviteeAuthContext(identity.getProvider(), identity.getProviderUserId()))
                .orElse(InviteeAuthContext.NONE);
    }

    private record InviteeAuthContext(AuthProvider provider, String providerUserId) {
        private static final InviteeAuthContext NONE = new InviteeAuthContext(null, null);
    }

    private String resolveBookingHostTimezone(Booking booking) {
        return userRepository.findById(booking.getHostId())
                .map(User::getTimezone)
                .orElse("UTC");
    }

    private String resolveUserTimezone(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getTimezone)
                .orElse("UTC");
    }

    private BookingLogContext buildConfirmContext(PublicBookingTargetResolver.ResolvedTarget target, UUID aggregateId) {
        if (target.kind() == EventKind.GROUP) {
            return sessionRegistrationRepository.findByIdAndHostId(aggregateId, target.userId())
                    .map(registration -> {
                        EventSession session = eventSessionRepository.findById(registration.getSessionId()).orElse(null);
                        return new BookingLogContext(
                                registration.getId(),
                                registration.getSessionId(),
                                target.userId(),
                                target.eventTypeId(),
                                registration.getGuestEmail(),
                                registration.getGuestName(),
                                session == null ? null : session.getStartTime(),
                                session == null ? null : session.getEndTime());
                    })
                    .orElse(new BookingLogContext(aggregateId, null, target.userId(), target.eventTypeId(), null, null, null, null));
        }
        return bookingRepository.findAnyByIdAndEventTypeId(aggregateId, target.eventTypeId())
                .map(booking -> new BookingLogContext(
                        booking.getId(),
                        null,
                        booking.getHostId(),
                        booking.getEventTypeId(),
                        booking.getGuestEmail(),
                        booking.getGuestName(),
                        booking.getStartTime(),
                        booking.getEndTime()))
                .orElse(new BookingLogContext(aggregateId, null, target.userId(), target.eventTypeId(), null, null, null, null));
    }

    private void emitConfirmSuccess(PublicBookingTargetResolver.ResolvedTarget target,
                                    UUID aggregateId,
                                    BookingLogContext context,
                                    long startedAtNanos) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        if (target.kind() == EventKind.GROUP) {
            OpsLoggers.BOOKING.info(
                    "group_registration_flow_completed registrationId={} sessionId={} hostId={} eventTypeId={} guestEmail={} guestNamePresent={} startTimeUtc={} endTimeUtc={} result=SUCCESS reasonCode=NONE slow={} elapsedMs={}",
                    aggregateId,
                    context.sessionId(),
                    context.hostId(),
                    context.eventTypeId(),
                    OpsLogSupport.maskEmail(context.guestEmail()),
                    context.guestName() != null && !context.guestName().isBlank(),
                    context.startTime(),
                    context.endTime(),
                    elapsedMs > 2000,
                    elapsedMs);
            return;
        }
        OpsLoggers.BOOKING.info(
                "booking_flow_completed bookingId={} hostId={} eventTypeId={} guestEmail={} guestNamePresent={} startTimeUtc={} endTimeUtc={} result=SUCCESS reasonCode=NONE slow={} elapsedMs={}",
                aggregateId,
                context.hostId(),
                context.eventTypeId(),
                OpsLogSupport.maskEmail(context.guestEmail()),
                context.guestName() != null && !context.guestName().isBlank(),
                context.startTime(),
                context.endTime(),
                elapsedMs > 2000,
                elapsedMs);
    }

    private void emitConfirmRejected(PublicBookingTargetResolver.ResolvedTarget target,
                                     UUID aggregateId,
                                     BookingLogContext context,
                                     CustomException exception,
                                     long startedAtNanos) {
        String reasonCode = OpsLogSupport.bookingReasonCode(exception);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        if (target.kind() == EventKind.GROUP) {
            OpsLoggers.BOOKING.info(
                    "group_registration_rejected registrationId={} sessionId={} hostId={} eventTypeId={} guestEmail={} reasonCode={} message={}",
                    aggregateId,
                    context.sessionId(),
                    context.hostId(),
                    context.eventTypeId(),
                    OpsLogSupport.maskEmail(context.guestEmail()),
                    reasonCode,
                    OpsLogSupport.truncate(exception.getMessage(), 160));
            OpsLoggers.BOOKING.info(
                    "group_registration_flow_completed registrationId={} sessionId={} hostId={} eventTypeId={} guestEmail={} startTimeUtc={} endTimeUtc={} result=REJECTED reasonCode={} slow={} elapsedMs={}",
                    aggregateId,
                    context.sessionId(),
                    context.hostId(),
                    context.eventTypeId(),
                    OpsLogSupport.maskEmail(context.guestEmail()),
                    context.startTime(),
                    context.endTime(),
                    reasonCode,
                    elapsedMs > 2000,
                    elapsedMs);
            return;
        }
        OpsLoggers.BOOKING.info(
                "booking_rejected bookingId={} hostId={} eventTypeId={} guestEmail={} reasonCode={} message={}",
                aggregateId,
                context.hostId(),
                context.eventTypeId(),
                OpsLogSupport.maskEmail(context.guestEmail()),
                reasonCode,
                OpsLogSupport.truncate(exception.getMessage(), 160));
        OpsLoggers.BOOKING.info(
                "booking_flow_completed bookingId={} hostId={} eventTypeId={} guestEmail={} startTimeUtc={} endTimeUtc={} result=REJECTED reasonCode={} slow={} elapsedMs={}",
                aggregateId,
                context.hostId(),
                context.eventTypeId(),
                OpsLogSupport.maskEmail(context.guestEmail()),
                context.startTime(),
                context.endTime(),
                reasonCode,
                elapsedMs > 2000,
                elapsedMs);
    }

    private void emitConfirmFailed(PublicBookingTargetResolver.ResolvedTarget target,
                                   UUID aggregateId,
                                   BookingLogContext context,
                                   RuntimeException exception,
                                   long startedAtNanos) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        if (target.kind() == EventKind.GROUP) {
            OpsLoggers.BOOKING.warn(
                    "group_registration_confirmation_failed registrationId={} sessionId={} hostId={} eventTypeId={} guestEmail={} reasonCode=INTERNAL_ERROR message={}",
                    aggregateId,
                    context.sessionId(),
                    context.hostId(),
                    context.eventTypeId(),
                    OpsLogSupport.maskEmail(context.guestEmail()),
                    OpsLogSupport.truncate(exception.getMessage(), 160));
            OpsLoggers.BOOKING.info(
                    "group_registration_flow_completed registrationId={} sessionId={} hostId={} eventTypeId={} guestEmail={} startTimeUtc={} endTimeUtc={} result=FAILED reasonCode=INTERNAL_ERROR slow={} elapsedMs={}",
                    aggregateId,
                    context.sessionId(),
                    context.hostId(),
                    context.eventTypeId(),
                    OpsLogSupport.maskEmail(context.guestEmail()),
                    context.startTime(),
                    context.endTime(),
                    elapsedMs > 2000,
                    elapsedMs);
            return;
        }
        OpsLoggers.BOOKING.warn(
                "booking_confirmation_failed bookingId={} hostId={} eventTypeId={} guestEmail={} reasonCode=INTERNAL_ERROR message={}",
                aggregateId,
                context.hostId(),
                context.eventTypeId(),
                OpsLogSupport.maskEmail(context.guestEmail()),
                OpsLogSupport.truncate(exception.getMessage(), 160));
        OpsLoggers.BOOKING.info(
                "booking_flow_completed bookingId={} hostId={} eventTypeId={} guestEmail={} startTimeUtc={} endTimeUtc={} result=FAILED reasonCode=INTERNAL_ERROR slow={} elapsedMs={}",
                aggregateId,
                context.hostId(),
                context.eventTypeId(),
                OpsLogSupport.maskEmail(context.guestEmail()),
                context.startTime(),
                context.endTime(),
                elapsedMs > 2000,
                elapsedMs);
    }

    private record BookingLogContext(UUID aggregateId,
                                     UUID sessionId,
                                     UUID hostId,
                                     UUID eventTypeId,
                                     String guestEmail,
                                     String guestName,
                                     Instant startTime,
                                     Instant endTime) {
    }

    private static SlotResponse notReadyAvailability(UUID userId,
                                                     UUID eventTypeId,
                                                     LocalDate date,
                                                     String timezone,
                                                     AvailabilityStatus status) {
        return new SlotResponse(userId, eventTypeId, date, timezone, 0L, Instant.now(), true, List.of(), status);
    }

}
