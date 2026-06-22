package io.bunnycal.sync.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.domain.AssignmentReason;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventRepository;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.sync.invariants.SyncInvariantMonitor;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import io.bunnycal.sync.state.InternalRefType;
import io.bunnycal.sync.state.SyncDesiredAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class OutboxProcessorTest {

    @Mock private OutboxEventRepository outboxRepository;
    @Mock private CalendarSyncJobRepository syncJobRepository;
    @Mock private BookingOutboxEventRouter eventRouter;
    @Mock private SyncInvariantMonitor invariantMonitor;
    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private BookingAssignmentRepository bookingAssignmentRepository;

    private OutboxProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new OutboxProcessor(outboxRepository, syncJobRepository, eventRouter, invariantMonitor,
                bookingRepository, eventTypeRepository, bookingAssignmentRepository, new SimpleMeterRegistry());
    }

    @Test
    void bookingConfirmedEvent_createsSyncJobAndMarksOutboxProcessed() {
        UUID outboxId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent();
        event.setId(outboxId);
        event.setAggregateType("Booking");
        event.setAggregateId(bookingId);
        event.setEventType("BOOKING_CONFIRMED");
        event.setPayload("{\"type\":\"BOOKING_CONFIRMED\",\"version\":1,\"payload\":{\"bookingId\":\"" + bookingId + "\"}}");
        event.setStatus(OutboxEventStatus.PROCESSING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(Instant.now());

        when(outboxRepository.claimBookingSyncEvents(any(), eq(10))).thenReturn(List.of(outboxId));
        when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(event));
        when(eventRouter.toPlan(event, "google"))
                .thenReturn(new SyncJobPlan(
                        InternalRefType.BOOKING,
                        bookingId,
                        SyncDesiredAction.CREATE,
                        "google"));
        // Non-COLLECTIVE booking: event-type lookup returns empty → isCollective=false.
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.empty());

        int claimed = processor.processBatch(10, "google");

        assertEquals(1, claimed);
        verify(syncJobRepository).upsertPendingJob(any(), eq("BOOKING"), eq(bookingId), eq("google"), eq("CREATE"), any());
        verify(outboxRepository).save(event);
        assertEquals(OutboxEventStatus.PROCESSED, event.getStatus());
    }

    @Test
    void collectiveBooking_confirmed_fansOutOneSyncJobPerAssignment() {
        UUID outboxId  = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID etId      = UUID.randomUUID();
        UUID aliceId   = UUID.randomUUID();
        UUID bobId     = UUID.randomUUID();
        UUID charlieId = UUID.randomUUID();

        OutboxEvent event = new OutboxEvent();
        event.setId(outboxId);
        event.setAggregateType("Booking");
        event.setAggregateId(bookingId);
        event.setEventType("BOOKING_CONFIRMED");
        event.setStatus(OutboxEventStatus.PROCESSING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(Instant.now());

        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setEventTypeId(etId);

        EventType et = EventType.builder().id(etId).kind(EventKind.COLLECTIVE).build();

        List<BookingAssignment> assignments = List.of(
                BookingAssignment.builder().bookingId(bookingId).participantUserId(aliceId).assignmentReason(AssignmentReason.COLLECTIVE_ALL).build(),
                BookingAssignment.builder().bookingId(bookingId).participantUserId(bobId).assignmentReason(AssignmentReason.COLLECTIVE_ALL).build(),
                BookingAssignment.builder().bookingId(bookingId).participantUserId(charlieId).assignmentReason(AssignmentReason.COLLECTIVE_ALL).build()
        );

        when(outboxRepository.claimBookingSyncEvents(any(), eq(10))).thenReturn(List.of(outboxId));
        when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(event));
        when(eventRouter.toPlan(event, "google"))
                .thenReturn(new SyncJobPlan(InternalRefType.BOOKING, bookingId, SyncDesiredAction.CREATE, "google"));
        when(bookingRepository.findAnyById(bookingId)).thenReturn(Optional.of(booking));
        when(eventTypeRepository.findById(etId)).thenReturn(Optional.of(et));
        when(bookingAssignmentRepository.findAllByBookingId(bookingId)).thenReturn(assignments);

        int claimed = processor.processBatch(10, "google");

        assertEquals(1, claimed);
        // Exactly 3 sync jobs — one per participant, each with their own partitionKey
        ArgumentCaptor<UUID> partitionKeyCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(syncJobRepository, times(3)).upsertPendingJob(
                any(), eq("BOOKING"), eq(bookingId), eq("google"), eq("CREATE"), any(),
                partitionKeyCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(partitionKeyCaptor.getAllValues())
                .containsExactlyInAnyOrder(aliceId, bobId, charlieId);
        assertEquals(OutboxEventStatus.PROCESSED, event.getStatus());
    }
}
