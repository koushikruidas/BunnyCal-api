package com.daedalussystems.easySchedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPublisher;
import com.daedalussystems.easySchedule.booking.outbox.OutboxPayloadEnvelope;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

class BookingConflictMetricsTest {

    private SimpleMeterRegistry registry;
    private BookingService service;

    private UserRepository userRepo;
    private BookingRepository bookingRepo;
    private OutboxPublisher publisher;
    private TimeSource timeSource;

    @BeforeEach
    void setUp() {
        registry    = new SimpleMeterRegistry();
        userRepo    = mock(UserRepository.class);
        bookingRepo = mock(BookingRepository.class);
        publisher   = mock(OutboxPublisher.class);
        timeSource  = mock(TimeSource.class);
        when(timeSource.now()).thenReturn(Instant.now());

        service = new BookingService(userRepo, bookingRepo, publisher, timeSource, registry);

        when(userRepo.findByIdForUpdate(any())).thenReturn(Optional.of(mock(User.class)));
        when(bookingRepo.countOverlappingPending(any(), any(), any())).thenReturn(0L);
        when(bookingRepo.saveAndFlush(any())).thenReturn(mock(Booking.class));
        when(bookingRepo.countByStatus(any())).thenReturn(0L);
    }

    @Test
    void conflictCounter_incrementsOnSlotAlreadyBooked() {
        // Arrange: saveAndFlush() now surfaces the EXCLUDE violation deterministically
        // via Spring Data's translated DataIntegrityViolationException — no longer
        // relies on incidental auto-flush inside publish().
        SQLException sqlCause = new SQLException(
                "conflicting key value violates exclusion constraint \"bookings_no_overlap\"",
                "23P01");
        DataIntegrityViolationException dive =
                new DataIntegrityViolationException("constraint violation", sqlCause);
        doThrow(dive).when(bookingRepo).saveAndFlush(any());

        UUID hostId      = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2025-01-01T09:00:00Z");
        Instant end      = Instant.parse("2025-01-01T10:00:00Z");

        // Act
        try {
            service.createBooking(hostId, eventTypeId, start, end);
        } catch (CustomException ex) {
            assertEquals(ErrorCode.SLOT_ALREADY_BOOKED, ex.getErrorCode());
        }

        // Assert
        Counter counter = registry.find("booking.conflicts.total").counter();
        assertEquals(1.0, counter.count(), 0.001,
                "booking.conflicts.total must increment exactly once per conflict");
        // publish() must NOT be called when saveAndFlush() throws first
        Mockito.verify(publisher, Mockito.never()).publish(
                any(String.class), nullable(UUID.class), any(UUID.class), any(OutboxPayloadEnvelope.class));
    }

    @Test
    void conflictCounter_doesNotIncrementOnOtherViolations() {
        // Non-overlap DataIntegrityViolationException (different SQLState)
        SQLException sqlCause = new SQLException("unique_violation", "23505");
        DataIntegrityViolationException dive =
                new DataIntegrityViolationException("other constraint", sqlCause);
        doThrow(dive).when(bookingRepo).saveAndFlush(any());

        try {
            service.createBooking(
                    UUID.randomUUID(), UUID.randomUUID(),
                    Instant.parse("2025-01-01T09:00:00Z"),
                    Instant.parse("2025-01-01T10:00:00Z"));
        } catch (CustomException ignored) {}

        Counter counter = registry.find("booking.conflicts.total").counter();
        assertEquals(0.0, counter.count(), 0.001,
                "booking.conflicts.total must not increment for unrelated constraint violations");
    }

    @Test
    void saveAndFlush_isCalledBeforePublish() {
        // Regression guard: createBooking must use saveAndFlush (not save) so the
        // EXCLUDE constraint violation surfaces synchronously here, before publish().
        // If anyone reverts to plain save(), the comment-documented auto-flush
        // dependency would re-emerge and overlap detection would silently break.
        UUID hostId      = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        Instant start    = Instant.parse("2025-01-01T09:00:00Z");
        Instant end      = Instant.parse("2025-01-01T10:00:00Z");

        service.createBooking(hostId, eventTypeId, start, end);

        InOrder inOrder = Mockito.inOrder(bookingRepo, publisher);
        inOrder.verify(bookingRepo).saveAndFlush(any());
        inOrder.verify(publisher).publish(
                any(String.class), nullable(UUID.class), eq(hostId), any(OutboxPayloadEnvelope.class));
        Mockito.verify(bookingRepo, Mockito.never()).save(any());
    }
}
