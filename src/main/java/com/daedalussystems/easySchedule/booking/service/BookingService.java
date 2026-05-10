package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.booking.contract.BookingStateTransitions;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.domain.BookingRules;
import com.daedalussystems.easySchedule.booking.domain.events.BookingCancelledEvent;
import com.daedalussystems.easySchedule.booking.domain.events.BookingConfirmedEvent;
import com.daedalussystems.easySchedule.booking.domain.events.BookingCreatedEvent;
import com.daedalussystems.easySchedule.booking.domain.events.BookingUpdatedEvent;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPayloadEnvelope;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /**
     * PostgreSQL SQLState for EXCLUDE constraint violation.
     */
    private static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";

    /**
     * Constraint name prefix (matches partitioned tables).
     */
    private static final String OVERLAP_CONSTRAINT = "bookings_no_overlap";

    /**
     * Protection against "phantom pending explosion".
     */
    public static final int MAX_PENDING_PER_HOST_PER_WINDOW = 3;

    /**
     * SLO: bookings must reach a terminal state within this many seconds.
     */
    static final long SLO_THRESHOLD_SECONDS = 5L;

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final OutboxPublisher outboxPublisher;
    private final TimeSource timeSource;

    // ── Metrics ──────────────────────────────────────────────────────────────
    private final Counter conflictCounter;
    private final Timer completionTimer;
    private final Counter bookingCompletedTotal;
    private final Counter bookingCompletedWithinSloTotal;

    public BookingService(
            UserRepository userRepository,
            BookingRepository bookingRepository,
            OutboxPublisher outboxPublisher,
            TimeSource timeSource,
            MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.outboxPublisher = outboxPublisher;
        this.timeSource = timeSource;

        this.conflictCounter = Counter.builder("booking.conflicts.total")
                .description("Number of booking attempts rejected due to slot overlap")
                .register(meterRegistry);

        // Gauge is pull-based: supplier is called at scrape time, not on every request.
        Gauge.builder("booking.pending.count", bookingRepository,
                        repo -> (double) repo.countByStatus("PENDING"))
                .description("Number of bookings currently in PENDING state")
                .register(meterRegistry);

        // publishPercentileHistogram() emits _bucket series required by histogram_quantile.
        this.completionTimer = Timer.builder("booking.completion.latency.seconds")
                .description("End-to-end booking lifecycle latency from creation to terminal state")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.bookingCompletedTotal = Counter.builder("booking.completed.total")
                .description("Total bookings that reached a terminal state")
                .register(meterRegistry);

        this.bookingCompletedWithinSloTotal = Counter.builder("booking.completed.within_slo.total")
                .description("Bookings that reached terminal state within the " + SLO_THRESHOLD_SECONDS + "-second SLO")
                .register(meterRegistry);
    }

    /**
     * Creates a booking with strict guarantees:
     *
     * - DB constraint is the ONLY authority for overlap
     * - App layer protects against pending explosion
     * - Booking + outbox event are atomically persisted
     */
    @Transactional
    public Booking createBooking(
            UUID hostId,
            UUID eventTypeId,
            Instant requestedStart,
            Instant requestedEnd,
            String guestEmail,
            String guestName) {

        // ─────────────────────────────────────────────────────────
        // 1. Input validation
        // ─────────────────────────────────────────────────────────
        if (hostId == null || eventTypeId == null || requestedStart == null || requestedEnd == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "hostId, eventTypeId, start, end are required.");
        }

        if (!requestedStart.isBefore(requestedEnd)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Booking start must be before end.");
        }

        // ─────────────────────────────────────────────────────────
        // 2. Lock host row (serialize concurrent bookings)
        // ─────────────────────────────────────────────────────────
        userRepository.findByIdForUpdate(hostId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Host not found."));

        // ─────────────────────────────────────────────────────────
        // 3. Anti-explosion guard (NOT correctness, only protection)
        // ─────────────────────────────────────────────────────────
        long pendingCount = bookingRepository.countOverlappingPending(
                hostId, requestedStart, requestedEnd);

        if (pendingCount >= MAX_PENDING_PER_HOST_PER_WINDOW) {
            throw new CustomException(ErrorCode.TOO_MANY_PENDING_BOOKINGS);
        }

        // ─────────────────────────────────────────────────────────
        // 4. Persist booking (DB constraint is authority)
        // ─────────────────────────────────────────────────────────
        Booking saved;
        try {
            saved = bookingRepository.save(Booking.builder()
                    .hostId(hostId)
                    .eventTypeId(eventTypeId)
                    .startTime(requestedStart)
                    .endTime(requestedEnd)
                    .guestEmail(guestEmail)
                    .guestName(guestName)
                    .build());

            // outboxPublisher.publish() invokes timeSource.now() which executes
            // SELECT now() — that DB read triggers Hibernate auto-flush, which is
            // when the deferred booking INSERT actually runs. The EXCLUDE constraint
            // violation therefore surfaces from inside publish(), not from save().
            // The catch must enclose this call so the violation can be translated.
            outboxPublisher.publish("Booking", saved.getId(), new OutboxPayloadEnvelope(
                    UUID.randomUUID().toString(),
                    "BOOKING_CREATED",
                    1,
                    new BookingCreatedEvent(saved.getId(), hostId, eventTypeId, requestedStart, requestedEnd)));

        } catch (DataIntegrityViolationException ex) {
            if (isOverlapExclusionViolation(ex)) {
                conflictCounter.increment();
                throw new CustomException(ErrorCode.SLOT_ALREADY_BOOKED);
            }
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return saved;
    }

    @Transactional
    public Booking createBooking(
            UUID hostId,
            UUID eventTypeId,
            Instant requestedStart,
            Instant requestedEnd) {
        return createBooking(hostId, eventTypeId, requestedStart, requestedEnd, null, null);
    }

    @Transactional
    public Booking createHeldBooking(UUID hostId,
                                     UUID eventTypeId,
                                     Instant requestedStart,
                                     Instant requestedEnd,
                                     Duration holdDuration,
                                     String guestEmail,
                                     String guestName) {
        if (holdDuration == null || holdDuration.isZero() || holdDuration.isNegative()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "holdDuration must be positive.");
        }
        Booking booking = createBooking(hostId, eventTypeId, requestedStart, requestedEnd, guestEmail, guestName);
        Instant expiresAt = timeSource.now().plus(holdDuration);
        int updated = bookingRepository.setPendingExpiry(booking.getId(), expiresAt);
        if (updated == 0) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        return booking;
    }

    @Transactional
    public Booking createHeldBooking(UUID hostId,
                                     UUID eventTypeId,
                                     Instant requestedStart,
                                     Instant requestedEnd,
                                     Duration holdDuration) {
        return createHeldBooking(hostId, eventTypeId, requestedStart, requestedEnd, holdDuration, null, null);
    }

    @Transactional
    public void confirmHeldBooking(UUID bookingId) {
        var row = bookingRepository.findStateById(bookingId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        if (!BookingState.PENDING.name().equals(row.getStatus())) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(timeSource.now())) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION, "Booking hold has expired.");
        }
        confirmBooking(bookingId, row.getVersion());
    }

    // ── State Transitions ────────────────────────────────────────────────────

    // DB result is the sole authority. Static check is a guardrail only
    // (fast-fail for provably illegal edges; throws VALIDATION_ERROR).
    // CAS miss (0 rows) → INVALID_STATE_TRANSITION.
    // Records completion latency when newStatus is terminal.
    private void transitionFromExpectedState(UUID id, BookingState expectedStatus,
                                             long version, BookingState newStatus) {
        BookingStateTransitions.requireAllowed(expectedStatus, newStatus);
        int updated = bookingRepository.updateStatus(
                id, expectedStatus.name(), newStatus.name(), version);
        if (updated == 0) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (newStatus.isTerminal()) {
            recordCompletionLatency(id);
        }
    }

    @Transactional
    public void confirmBooking(UUID id, long version) {
        transitionFromExpectedState(id, BookingState.PENDING, version, BookingState.CONFIRMED);
        bookingRepository.findAnyById(id).ifPresent(booking ->
                outboxPublisher.publish("Booking", id, new OutboxPayloadEnvelope(
                        UUID.randomUUID().toString(),
                        "BOOKING_CONFIRMED",
                        1,
                        new BookingConfirmedEvent(id, booking.getHostId()))));
    }

    @Transactional
    public void cancelPendingBooking(UUID id, long version) {
        transitionFromExpectedState(id, BookingState.PENDING, version, BookingState.CANCELLED);
    }

    @Transactional
    public void cancelConfirmedBooking(UUID id, long version) {
        transitionFromExpectedState(id, BookingState.CONFIRMED, version, BookingState.CANCELLED);
    }

    @Transactional
    public void expireBooking(UUID id, long version) {
        // Specialized CAS: same pattern as transitionFromExpectedState but the WHERE clause
        // also asserts expires_at < NOW(), preventing premature or double-expiry atomically.
        int updated = bookingRepository.expireIfPendingAndExpired(id, version);
        if (updated == 0) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        recordCompletionLatency(id);
    }

    @Transactional
    public void completeBooking(UUID id, long version) {
        transitionFromExpectedState(id, BookingState.CONFIRMED, version, BookingState.COMPLETED);
    }

    @Transactional
    public void updateBooking(UUID id, UUID hostId, Instant startTime, Instant endTime, long version) {
        BookingRules.validateReschedule(id, hostId, startTime, endTime);

        int updated = bookingRepository.updateWindow(id, hostId, startTime, endTime, version);
        if (updated == 0) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION);
        }

        outboxPublisher.publish("Booking", id, new OutboxPayloadEnvelope(
                UUID.randomUUID().toString(),
                "BOOKING_UPDATED",
                1,
                new BookingUpdatedEvent(id, hostId, startTime, endTime)));
    }

    @Transactional
    public void cancelBooking(UUID id, UUID hostId, long version) {
        if (id == null || hostId == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "id and hostId are required.");
        }

        int updated = bookingRepository.updateStatus(id, BookingState.PENDING.name(), BookingState.CANCELLED.name(), version);
        if (updated == 0) {
            updated = bookingRepository.updateStatus(id, BookingState.CONFIRMED.name(), BookingState.CANCELLED.name(), version);
        }
        if (updated == 0) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION);
        }

        outboxPublisher.publish("Booking", id, new OutboxPayloadEnvelope(
                UUID.randomUUID().toString(),
                "BOOKING_CANCELLED",
                1,
                new BookingCancelledEvent(id, hostId)));
    }

    // ── SLO instrumentation ──────────────────────────────────────────────────

    // Called after every successful terminal-state CAS. Fetches created_at from DB
    // to compute true end-to-end duration (includes outbox lag and retries).
    // Uses timeSource.now() for clock consistency with the rest of the system.
    // Negative duration guard defends against DB/app clock skew.
    // Wrapped in try-catch: metric failure must never abort the business transaction.
    private void recordCompletionLatency(UUID id) {
        try {
            Instant createdAt = bookingRepository.findCreatedAtById(id).orElse(null);
            if (createdAt == null) return;
            Duration duration = Duration.between(createdAt, timeSource.now());
            if (duration.isNegative()) return;
            completionTimer.record(duration);
            bookingCompletedTotal.increment();
            if (duration.compareTo(Duration.ofSeconds(SLO_THRESHOLD_SECONDS)) <= 0) {
                bookingCompletedWithinSloTotal.increment();
            }
        } catch (Exception ex) {
            log.warn("booking.completion.latency.record.failed id={}", id, ex);
        }
    }

    /**
     * Detects whether a DataIntegrityViolationException was caused by
     * the bookings_no_overlap EXCLUDE constraint.
     */
    private static boolean isOverlapExclusionViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {

            if (cause instanceof SQLException sqlEx
                    && SQLSTATE_EXCLUSION_VIOLATION.equals(sqlEx.getSQLState())) {

                String msg = sqlEx.getMessage();

                return msg != null && msg.contains(OVERLAP_CONSTRAINT);
            }
        }
        return false;
    }

}
