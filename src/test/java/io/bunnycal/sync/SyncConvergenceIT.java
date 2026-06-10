package io.bunnycal.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.repository.CalendarEventMappingRepository;
import io.bunnycal.booking.repository.CalendarEventMappingRepository.ClaimOutcome;
import io.bunnycal.calendar.service.CalendarService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SyncConvergenceIT extends AbstractBookingIT {

    @Autowired
    private SyncWorker syncWorker;

    @Autowired
    private CalendarEventMappingRepository repository;

    @Autowired
    private FencingTokenGenerator tokenGenerator;

    @MockitoBean
    private CalendarService calendarService;

    @Test
    void highContention_convergesToSingleCreatedMapping_noStuckClaimed() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        UUID participantUserId = UUID.randomUUID();
        var eventsByKey = new ConcurrentHashMap<String, String>();

        when(calendarService.createEvent(any(CalendarService.CreateCalendarEventCommand.class)))
                .thenAnswer(invocation -> {
                    CalendarService.CreateCalendarEventCommand cmd = invocation.getArgument(0);
                    UUID b = cmd.internalId();
                    String p = cmd.provider();
                    String key = cmd.idempotencyKey();
                    return CalendarService.CreateEventResult.success(
                            eventsByKey.computeIfAbsent(key, ignored -> p + "-event-" + b));
                });

        int workers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                syncWorker.processBookingSync(bookingId, provider, participantUserId);
                return null;
            }));
        }
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        for (Future<?> f : futures) {
            f.get();
        }

        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId));
        String externalEventId = jdbc.queryForObject(
                "SELECT external_event_id FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId);
        assertNotNull(externalEventId);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ? AND status = 'CLAIMED'",
                Integer.class, bookingId, provider, participantUserId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ? AND external_event_id IS NOT NULL",
                Integer.class, bookingId, provider, participantUserId));
    }

    @Test
    void crashAfterProviderSuccess_convergesOnRetry_withoutLogicalDuplicate() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        UUID participantUserId = UUID.randomUUID();
        String idempotencyKey = provider + ":" + bookingId + ":" + participantUserId;
        var eventsByKey = new ConcurrentHashMap<String, String>();

        when(calendarService.createEvent(any(CalendarService.CreateCalendarEventCommand.class)))
                .thenAnswer(invocation -> {
                    CalendarService.CreateCalendarEventCommand cmd = invocation.getArgument(0);
                    UUID b = cmd.internalId();
                    String p = cmd.provider();
                    String key = cmd.idempotencyKey();
                    return CalendarService.CreateEventResult.success(
                            eventsByKey.computeIfAbsent(key, ignored -> p + "-event-" + b));
                });

        long token = tokenGenerator.nextToken();
        assertEquals(ClaimOutcome.CLAIMED, repository.claimBookingForSync(bookingId, provider, participantUserId, token, "crashed-worker"));
        String firstExternal = calendarService.createEvent(
                new CalendarService.CreateCalendarEventCommand(bookingId, provider, idempotencyKey)).externalEventId();
        assertNotNull(firstExternal);

        syncWorker.processBookingSync(bookingId, provider, participantUserId);

        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId));
        assertEquals(firstExternal, jdbc.queryForObject(
                "SELECT external_event_id FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId));
    }

    @Test
    void timeoutThenRetry_converges_withoutLogicalDuplicate() {
        UUID bookingId = UUID.randomUUID();
        String provider = "google";
        UUID participantUserId = UUID.randomUUID();
        var eventsByKey = new ConcurrentHashMap<String, String>();
        AtomicInteger calls = new AtomicInteger(0);

        when(calendarService.createEvent(any(CalendarService.CreateCalendarEventCommand.class)))
                .thenAnswer(invocation -> {
                    CalendarService.CreateCalendarEventCommand cmd = invocation.getArgument(0);
                    UUID b = cmd.internalId();
                    String p = cmd.provider();
                    String key = cmd.idempotencyKey();
                    String eventId = eventsByKey.computeIfAbsent(key, ignored -> p + "-event-" + b);
                    if (calls.incrementAndGet() == 1) {
                        return CalendarService.CreateEventResult.retryable("HTTP_429");
                    }
                    return CalendarService.CreateEventResult.success(eventId);
                });

        syncWorker.processBookingSync(bookingId, provider, participantUserId);
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId));

        syncWorker.processBookingSync(bookingId, provider, participantUserId);

        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId));
        assertNotNull(jdbc.queryForObject(
                "SELECT external_event_id FROM calendar_event_mappings WHERE booking_id = ? AND provider = ? AND participant_user_id = ?",
                String.class, bookingId, provider, participantUserId));
        verify(calendarService, atLeast(2)).createEvent(any());
    }
}
