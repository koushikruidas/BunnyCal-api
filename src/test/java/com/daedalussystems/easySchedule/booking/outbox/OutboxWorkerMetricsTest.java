package com.daedalussystems.easySchedule.booking.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.common.time.TimeSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OutboxWorkerMetricsTest {

    // ── Fake transaction manager ──────────────────────────────────────────────
    // Executes TransactionTemplate callbacks synchronously without a real DB.
    // commit() and rollback() are no-ops so the lambda always runs to completion.
    private static final PlatformTransactionManager FAKE_TM = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition def) {
            return new SimpleTransactionStatus(true);
        }
        @Override
        public void commit(TransactionStatus status) {}
        @Override
        public void rollback(TransactionStatus status) {}
    };

    private SimpleMeterRegistry registry;
    private OutboxWorker worker;

    private OutboxEventRepository outboxRepo;
    private ProcessedEventRepository processedEventRepo;
    private OutboxEventDispatcher dispatcher;
    private TimeSource timeSource;

    @BeforeEach
    void setUp() {
        registry         = new SimpleMeterRegistry();
        outboxRepo       = mock(OutboxEventRepository.class);
        processedEventRepo = mock(ProcessedEventRepository.class);
        dispatcher       = mock(OutboxEventDispatcher.class);
        timeSource       = mock(TimeSource.class);

        when(timeSource.now()).thenReturn(Instant.now());

        worker = new OutboxWorker(
                outboxRepo, processedEventRepo, dispatcher, timeSource, FAKE_TM, registry);
    }

    // ─────────────────────────────────────────────────────────────
    // outbox.retries.total increments when dispatch fails (RETRYING)
    // ─────────────────────────────────────────────────────────────
    @Test
    void retryCounter_incrementsOnDispatchFailure() {
        UUID id = UUID.randomUUID();
        OutboxEvent event = buildEvent(id, OutboxEventStatus.PROCESSING, 0);

        when(outboxRepo.claimBatch(any(), anyInt())).thenReturn(List.of(id));
        when(outboxRepo.findById(id)).thenReturn(Optional.of(event));
        when(processedEventRepo.tryInsert(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("downstream failure")).when(dispatcher).dispatch(any());

        worker.poll();

        Counter retryCounter = registry.find("outbox.retries.total").counter();
        assertEquals(1.0, retryCounter.count(), 0.001,
                "outbox.retries.total must increment once when event enters RETRYING");
    }

    @Test
    void retryCounter_doesNotIncrementWhenDlqReached() {
        UUID id = UUID.randomUUID();
        // One attempt short of DLQ → this failure pushes it to FAILED, not RETRYING
        OutboxEvent event = buildEvent(id, OutboxEventStatus.PROCESSING,
                com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS - 1);

        when(outboxRepo.claimBatch(any(), anyInt())).thenReturn(List.of(id));
        when(outboxRepo.findById(id)).thenReturn(Optional.of(event));
        when(processedEventRepo.tryInsert(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("downstream failure")).when(dispatcher).dispatch(any());

        worker.poll();

        Counter retryCounter = registry.find("outbox.retries.total").counter();
        assertEquals(0.0, retryCounter.count(), 0.001,
                "outbox.retries.total must NOT increment when event moves to FAILED (DLQ)");

        Counter bookingFailed = registry.find("booking.failed.total").counter();
        assertEquals(1.0, bookingFailed.count(), 0.001,
                "booking.failed.total must increment when a Booking event reaches DLQ");
    }

    @Test
    void bookingFailedCounter_doesNotIncrementForNonBookingAggregate() {
        UUID id = UUID.randomUUID();
        OutboxEvent event = buildEvent(id, OutboxEventStatus.PROCESSING,
                com.daedalussystems.easySchedule.booking.contract.BookingContracts.OUTBOX_MAX_ATTEMPTS - 1);
        event.setAggregateType("Notification");

        when(outboxRepo.claimBatch(any(), anyInt())).thenReturn(List.of(id));
        when(outboxRepo.findById(id)).thenReturn(Optional.of(event));
        when(processedEventRepo.tryInsert(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("downstream failure")).when(dispatcher).dispatch(any());

        worker.poll();

        Counter bookingFailed = registry.find("booking.failed.total").counter();
        assertEquals(0.0, bookingFailed.count(), 0.001,
                "booking.failed.total must NOT increment for non-Booking aggregate types");
    }

    // ─────────────────────────────────────────────────────────────
    // outbox.processing.latency.seconds timer records on every processOne call
    // ─────────────────────────────────────────────────────────────
    @Test
    void processingLatencyTimer_recordsOnSuccessfulDispatch() {
        UUID id = UUID.randomUUID();
        OutboxEvent event = buildEvent(id, OutboxEventStatus.PROCESSING, 0);

        when(outboxRepo.claimBatch(any(), anyInt())).thenReturn(List.of(id));
        when(outboxRepo.findById(id)).thenReturn(Optional.of(event));
        when(processedEventRepo.tryInsert(any(), any())).thenReturn(1);

        worker.poll();

        Timer timer = registry.find("outbox.processing.latency.seconds").timer();
        assertEquals(1, timer.count(),
                "outbox.processing.latency.seconds must record once per processed event");
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────
    private static OutboxEvent buildEvent(UUID id, OutboxEventStatus status, int attemptCount) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setStatus(status);
        e.setAttemptCount(attemptCount);
        e.setCreatedAt(Instant.now().minusSeconds(5));
        e.setEventType("BOOKING_CONFIRMED");
        e.setAggregateType("Booking");
        e.setAggregateId(UUID.randomUUID());
        e.setPayload("{}");
        return e;
    }
}
