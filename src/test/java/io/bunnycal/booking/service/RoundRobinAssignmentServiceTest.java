package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.engine.SlotGenerationEngine.SlotUtc;
import io.bunnycal.availability.service.EventTypeParticipantService;
import io.bunnycal.availability.service.ParticipantAvailabilityService;
import io.bunnycal.availability.service.ParticipantEligibilityReason;
import io.bunnycal.availability.service.ParticipantEligibilityResult;
import io.bunnycal.availability.service.ParticipantEligibilityService;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoundRobinAssignmentServiceTest {

    @Mock private EventTypeParticipantService participantService;
    @Mock private ParticipantEligibilityService eligibilityService;
    @Mock private ParticipantAvailabilityService participantAvailabilityService;
    @Mock private BookingAssignmentRepository bookingAssignmentRepository;
    @Mock private BookingService bookingService;
    @Mock private UserRepository userRepository;

    private RoundRobinAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new RoundRobinAssignmentService(
                participantService,
                eligibilityService,
                participantAvailabilityService,
                bookingAssignmentRepository,
                bookingService,
                userRepository);
    }

    @Test
    void assignAndCreateHeldBooking_usesLeastRecentlyAssignedParticipant() {
        UUID ownerId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-15T09:00:00Z");
        Instant end = Instant.parse("2026-06-15T09:30:00Z");
        EventType eventType = EventType.builder()
                .id(eventTypeId)
                .userId(ownerId)
                .kind(EventKind.ROUND_ROBIN)
                .build();

        when(participantService.effectiveParticipantUserIds(eventType)).thenReturn(List.of(aliceId, bobId));
        when(eligibilityService.checkForRoundRobin(aliceId))
                .thenReturn(new ParticipantEligibilityResult(aliceId, true, ParticipantEligibilityReason.ACTIVE));
        when(eligibilityService.checkForRoundRobin(bobId))
                .thenReturn(new ParticipantEligibilityResult(bobId, true, ParticipantEligibilityReason.ACTIVE));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(User.builder().id(aliceId).timezone("UTC").build()));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(User.builder().id(bobId).timezone("UTC").build()));
        when(participantAvailabilityService.computeForParticipant(eq(aliceId), eq(eventType), eq(LocalDate.of(2026, 6, 15)), any()))
                .thenReturn(List.of(new SlotUtc(start, end)));
        when(participantAvailabilityService.computeForParticipant(eq(bobId), eq(eventType), eq(LocalDate.of(2026, 6, 15)), any()))
                .thenReturn(List.of(new SlotUtc(start, end)));
        when(bookingAssignmentRepository.findStatsForEventTypeAndParticipants(eventTypeId, List.of(aliceId, bobId)))
                .thenReturn(List.of(statsRow(aliceId, 3L, Instant.parse("2026-06-14T10:00:00Z"))));

        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .hostId(bobId)
                .eventTypeId(eventTypeId)
                .startTime(start)
                .endTime(end)
                .build();
        when(bookingService.createHeldBooking(
                bobId,
                eventTypeId,
                start,
                end,
                Duration.ofMinutes(10),
                "guest@example.com",
                "Guest"))
                .thenReturn(booking);

        var assigned = service.assignAndCreateHeldBooking(
                eventType,
                start,
                end,
                List.of(aliceId, bobId),
                Duration.ofMinutes(10),
                "guest@example.com",
                "Guest");

        assertEquals(bobId, assigned.participantUserId());
        assertEquals(AssignmentReason.LEAST_RECENTLY_ASSIGNED, assigned.assignmentReason());
        verify(bookingService, never()).createHeldBooking(
                eq(aliceId),
                eq(eventTypeId),
                eq(start),
                eq(end),
                any(),
                any(),
                any());
        ArgumentCaptor<BookingAssignment> captor = ArgumentCaptor.forClass(BookingAssignment.class);
        verify(bookingAssignmentRepository).save(captor.capture());
        assertEquals(booking.getId(), captor.getValue().getBookingId());
        assertEquals(bobId, captor.getValue().getParticipantUserId());
    }

    @Test
    void assignAndCreateHeldBooking_rejectsWhenNoCandidateStillOwnsSlot() {
        UUID ownerId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID aliceId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-15T09:00:00Z");
        Instant end = Instant.parse("2026-06-15T09:30:00Z");
        EventType eventType = EventType.builder()
                .id(eventTypeId)
                .userId(ownerId)
                .kind(EventKind.ROUND_ROBIN)
                .build();

        when(participantService.effectiveParticipantUserIds(eventType)).thenReturn(List.of(aliceId));
        when(eligibilityService.checkForRoundRobin(aliceId))
                .thenReturn(new ParticipantEligibilityResult(aliceId, true, ParticipantEligibilityReason.ACTIVE));
        when(userRepository.findById(aliceId)).thenReturn(Optional.of(User.builder().id(aliceId).timezone("UTC").build()));
        when(participantAvailabilityService.computeForParticipant(eq(aliceId), eq(eventType), eq(LocalDate.of(2026, 6, 15)), any()))
                .thenReturn(List.of());

        CustomException ex = assertThrows(CustomException.class, () -> service.assignAndCreateHeldBooking(
                eventType,
                start,
                end,
                List.of(aliceId),
                Duration.ofMinutes(10),
                "guest@example.com",
                "Guest"));
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, ex.getErrorCode());
    }

    private BookingAssignmentRepository.ParticipantAssignmentStatsRow statsRow(UUID participantId,
                                                                               long assignmentCount,
                                                                               Instant lastAssignedAt) {
        return new BookingAssignmentRepository.ParticipantAssignmentStatsRow() {
            @Override public UUID getParticipantUserId() { return participantId; }
            @Override public long getAssignmentCount() { return assignmentCount; }
            @Override public Instant getLastAssignedAt() { return lastAssignedAt; }
        };
    }
}
