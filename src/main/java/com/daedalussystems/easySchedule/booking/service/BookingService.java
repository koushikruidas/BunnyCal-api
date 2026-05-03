package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

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

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final SlotCacheService slotCacheService;
    private final OutboxPublisher outboxPublisher;

    public BookingService(
            UserRepository userRepository,
            BookingRepository bookingRepository,
            SlotCacheService slotCacheService,
            OutboxPublisher outboxPublisher) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.slotCacheService = slotCacheService;
        this.outboxPublisher = outboxPublisher;
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
            Instant requestedEnd) {

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
                    .build());

            // outboxPublisher.publish() invokes timeSource.now() which executes
            // SELECT now() — that DB read triggers Hibernate auto-flush, which is
            // when the deferred booking INSERT actually runs. The EXCLUDE constraint
            // violation therefore surfaces from inside publish(), not from save().
            // The catch must enclose this call so the violation can be translated.
            outboxPublisher.publish("Booking", saved.getId(), "BOOKING_CREATED", saved);

        } catch (DataIntegrityViolationException ex) {
            if (isOverlapExclusionViolation(ex)) {
                throw new CustomException(ErrorCode.SLOT_ALREADY_BOOKED);
            }
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        slotCacheService.invalidateUser(hostId);
        return saved;
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