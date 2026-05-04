package com.daedalussystems.easySchedule.sync.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEventRepository;
import com.daedalussystems.easySchedule.booking.outbox.OutboxEventStatus;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class OutboxProcessorTest {

    @Mock
    private OutboxEventRepository outboxRepository;
    @Mock
    private CalendarSyncJobRepository syncJobRepository;
    @Mock
    private BookingOutboxEventRouter eventRouter;

    private OutboxProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new OutboxProcessor(outboxRepository, syncJobRepository, eventRouter, new SimpleMeterRegistry());
    }

    @Test
    void bookingCreatedEvent_createsSyncJobAndMarksOutboxProcessed() {
        UUID outboxId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent();
        event.setId(outboxId);
        event.setAggregateType("Booking");
        event.setAggregateId(bookingId);
        event.setEventType("BOOKING_CREATED");
        event.setPayload("{\"type\":\"BOOKING_CREATED\",\"version\":1,\"payload\":{\"bookingId\":\"" + bookingId + "\"}}");
        event.setStatus(OutboxEventStatus.PROCESSING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(Instant.now());

        when(outboxRepository.claimBookingSyncEvents(any(), eq(10))).thenReturn(List.of(outboxId));
        when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(event));
        when(eventRouter.toPlan(event, "google"))
                .thenReturn(new SyncJobPlan(
                        com.daedalussystems.easySchedule.sync.state.InternalRefType.BOOKING,
                        bookingId,
                        com.daedalussystems.easySchedule.sync.state.SyncDesiredAction.CREATE,
                        "google"));

        int claimed = processor.processBatch(10, "google");

        assertEquals(1, claimed);
        verify(syncJobRepository).upsertPendingJob(any(), eq("BOOKING"), eq(bookingId), eq("google"), eq("CREATE"), any());
        verify(outboxRepository).save(event);
        assertEquals(OutboxEventStatus.PROCESSED, event.getStatus());
    }
}
