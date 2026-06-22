package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.availability.service.ParticipantEligibilityService;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.repository.CollectiveParticipantHoldRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates hold creation for COLLECTIVE bookings.
 *
 * <p>All N participant holds plus the Booking row and N BookingAssignment rows are written
 * in a single transaction. An EXCLUDE constraint violation on any hold INSERT (PSQLException
 * SQLState 23P01) rolls back the entire transaction and surfaces as SLOT_UNAVAILABLE.
 */
@Service
public class CollectiveAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(CollectiveAssignmentService.class);
    private static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";

    private final EventTypeParticipantService participantService;
    private final ParticipantEligibilityService eligibilityService;
    private final BookingService bookingService;
    private final BookingAssignmentRepository assignmentRepository;
    private final CollectiveParticipantHoldRepository holdRepository;

    public CollectiveAssignmentService(EventTypeParticipantService participantService,
                                       ParticipantEligibilityService eligibilityService,
                                       BookingService bookingService,
                                       BookingAssignmentRepository assignmentRepository,
                                       CollectiveParticipantHoldRepository holdRepository) {
        this.participantService = participantService;
        this.eligibilityService = eligibilityService;
        this.bookingService = bookingService;
        this.assignmentRepository = assignmentRepository;
        this.holdRepository = holdRepository;
    }

    public record CreatedCollectiveBooking(Booking booking, List<UUID> participantIds) {}

    /** Returns the current effective participant list for a collective event type. */
    public List<UUID> currentParticipantIds(EventType eventType) {
        return participantService.effectiveParticipantUserIds(eventType);
    }

    /**
     * Creates a held Collective booking.
     *
     * <p>The caller must have already validated the slot token's roster hash against
     * {@code participantIds} before calling this method.
     *
     * <ol>
     *   <li>Re-checks every participant: rules + calendar + writeback — hard block if any fail.</li>
     *   <li>Creates the Booking row (owner = host_id).</li>
     *   <li>Inserts one row per participant into {@code collective_participant_holds} inside the
     *       same transaction. An EXCLUDE violation rolls back the whole transaction → SLOT_UNAVAILABLE.</li>
     *   <li>Persists N {@code BookingAssignment} rows with reason {@code COLLECTIVE_ALL}.</li>
     * </ol>
     *
     * @param eventType      the Collective event type
     * @param ownerUserId    event type owner (becomes booking host_id)
     * @param start          slot start (UTC)
     * @param end            slot end (UTC)
     * @param participantIds validated current participant roster
     * @param holdDuration   how long the hold lasts before expiry
     * @param guestEmail     normalised guest email (nullable)
     * @param guestName      normalised guest name (nullable)
     */
    @Transactional
    public CreatedCollectiveBooking createHeldBooking(EventType eventType,
                                                      UUID ownerUserId,
                                                      Instant start,
                                                      Instant end,
                                                      List<UUID> participantIds,
                                                      Duration holdDuration,
                                                      String guestEmail,
                                                      String guestName) {
        // 1. Re-check every participant: rules + calendar + writeback.
        for (UUID participantId : participantIds) {
            var eligibility = eligibilityService.checkForRoundRobin(participantId);
            if (!eligibility.eligible()) {
                log.info("collective_hold_rejected_participant_ineligible eventTypeId={} participantId={} reason={}",
                        eventType.getId(), participantId, eligibility.reason());
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "A participant is no longer available. Please select a new time slot.");
            }
            if (!eligibilityService.hasActiveCalendar(participantId)) {
                log.info("collective_hold_rejected_no_calendar eventTypeId={} participantId={}",
                        eventType.getId(), participantId);
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "A participant's calendar is not connected. Please try again later.");
            }
            if (!eligibilityService.hasWritebackCapability(participantId)) {
                log.info("collective_hold_rejected_no_writeback eventTypeId={} participantId={}",
                        eventType.getId(), participantId);
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "A participant's calendar does not support event creation. Please try again later.");
            }
        }

        // 2. Create the booking row (owner is host_id).
        Booking booking;
        try {
            booking = bookingService.createHeldBooking(
                    ownerUserId, eventType.getId(), start, end, holdDuration, guestEmail, guestName);
        } catch (CustomException ex) {
            // Propagate SLOT_ALREADY_BOOKED / TOO_MANY_PENDING_BOOKINGS as SLOT_UNAVAILABLE.
            if (ex.getErrorCode() == ErrorCode.SLOT_ALREADY_BOOKED
                    || ex.getErrorCode() == ErrorCode.TOO_MANY_PENDING_BOOKINGS) {
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "This time slot is no longer available.");
            }
            throw ex;
        }

        Instant expiresAt = Instant.now().plus(holdDuration);

        // 3. Insert one hold per participant. Any EXCLUDE violation → whole tx rolls back.
        try {
            for (UUID participantId : participantIds) {
                holdRepository.insertHold(booking.getId(), participantId, start, end, expiresAt);
            }
        } catch (DataIntegrityViolationException ex) {
            if (isExclusionViolation(ex)) {
                throw new CustomException(ErrorCode.SLOT_UNAVAILABLE,
                        "This time slot is no longer available for one or more participants.");
            }
            throw ex;
        }

        // 4. Persist one BookingAssignment per participant.
        for (UUID participantId : participantIds) {
            assignmentRepository.save(BookingAssignment.builder()
                    .bookingId(booking.getId())
                    .participantUserId(participantId)
                    .assignmentReason(AssignmentReason.COLLECTIVE_ALL)
                    .build());
        }

        log.info("collective_hold_created bookingId={} eventTypeId={} participantCount={} start={} end={}",
                booking.getId(), eventType.getId(), participantIds.size(), start, end);
        return new CreatedCollectiveBooking(booking, participantIds);
    }

    private static boolean isExclusionViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlEx
                    && SQLSTATE_EXCLUSION_VIOLATION.equals(sqlEx.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
