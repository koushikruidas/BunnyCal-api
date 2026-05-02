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
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    // PostgreSQL SQLState for an EXCLUDE-constraint violation. The
    // bookings_no_overlap constraint raises this when two PENDING/CONFIRMED
    // bookings collide on the same host's time range.
    private static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";
    private static final String OVERLAP_CONSTRAINT = "bookings_no_overlap";

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

    @Transactional
    public Booking createBooking(UUID hostId, UUID eventTypeId, Instant requestedStart, Instant requestedEnd) {
        if (hostId == null || eventTypeId == null || requestedStart == null || requestedEnd == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "hostId, eventTypeId, start, end are required.");
        }
        if (!requestedStart.isBefore(requestedEnd)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Booking start must be before end.");
        }

        // Lock host row (SELECT ... FOR UPDATE). UserRepository's naming
        // reflects the auth domain — "host" is modeled as a User here.
        userRepository.findByIdForUpdate(hostId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Host not found."));

        // Application-level overlap pre-check. SAFETY NET only — the
        // authoritative enforcer is the bookings_no_overlap EXCLUDE
        // constraint (Invariant #1). This branch translates the
        // common case to a friendly error before reaching the DB and
        // is scheduled for removal once the Testcontainers test
        // proves 23P01 fires deterministically.
        List<Booking> overlaps = bookingRepository.findByHostIdAndStartTimeLessThanAndEndTimeGreaterThan(
                hostId,
                requestedEnd,
                requestedStart);
        if (!overlaps.isEmpty()) {
            throw new CustomException(ErrorCode.SLOT_ALREADY_BOOKED);
        }

        // Authoritative path. If a racer slipped past the pre-check
        // above, the EXCLUDE constraint raises 23P01 inside save() and
        // we translate to the same domain error.
        Booking saved;
        try {
            saved = bookingRepository.save(Booking.builder()
                    .hostId(hostId)
                    .eventTypeId(eventTypeId)
                    .startTime(requestedStart)
                    .endTime(requestedEnd)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            if (isOverlapExclusionViolation(ex)) {
                throw new CustomException(ErrorCode.SLOT_ALREADY_BOOKED);
            }
            throw ex;
        }

        // Event written in the same TX as the booking row — atomically
        // durable. Worker picks it up asynchronously after commit.
        outboxPublisher.publish("Booking", saved.getId(), "BOOKING_CREATED", saved);

        // Follow-up: SlotCacheService.invalidateUser is a host-scoped
        // operation; method name lags the rename. Rename to
        // invalidateHost / invalidatePrincipal in a separate change so
        // this commit's blast radius stays inside the booking module.
        slotCacheService.invalidateUser(hostId);
        return saved;
    }

    private static boolean isOverlapExclusionViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqle
                    && SQLSTATE_EXCLUSION_VIOLATION.equals(sqle.getSQLState())) {
                String msg = sqle.getMessage();
                // Be specific: only translate violations of OUR overlap
                // constraint. A future EXCLUDE on this table would
                // otherwise get silently mis-mapped here.
                return msg != null && msg.contains(OVERLAP_CONSTRAINT);
            }
        }
        return false;
    }
}
