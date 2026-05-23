package com.daedalussystems.easySchedule.booking.outbox;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.notification.BookingNotificationService;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.sync.invariants.SyncInvariantMonitor;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoggingOutboxEventDispatcherTest {

    @Mock
    private CalendarSyncJobRepository calendarSyncJobRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private EventTypeRepository eventTypeRepository;
    @Mock
    private CalendarConnectionRepository calendarConnectionRepository;
    @Mock
    private BookingNotificationService bookingNotificationService;
    @Mock
    private SyncInvariantMonitor invariantMonitor;

    private LoggingOutboxEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new LoggingOutboxEventDispatcher(
                calendarSyncJobRepository,
                bookingRepository,
                eventTypeRepository,
                calendarConnectionRepository,
                bookingNotificationService,
                invariantMonitor,
                "google",
                false);
    }

    @Test
    void dispatch_bookingConfirmed_enqueuesSyncJob() {
        UUID bookingId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("BOOKING"),
                eq(bookingId),
                eq("google"),
                eq("CREATE"),
                eq(null),
                eq(null), eq(null));
    }

    @Test
    void dispatch_bookingConfirmed_optionalModeWithoutActiveConnection_skipsSyncJob() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        LoggingOutboxEventDispatcher optionalDispatcher = new LoggingOutboxEventDispatcher(
                calendarSyncJobRepository,
                bookingRepository,
                eventTypeRepository,
                calendarConnectionRepository,
                bookingNotificationService,
                invariantMonitor,
                "google",
                true);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .build()));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                eq(hostId),
                eq(com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType.GOOGLE),
                eq(com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus.ACTIVE)))
                .thenReturn(java.util.Optional.empty());

        optionalDispatcher.dispatch(event);

        verify(calendarSyncJobRepository, never()).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatch_bookingConfirmed_optionalModeWithActiveConnection_enqueuesSyncJob() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        LoggingOutboxEventDispatcher optionalDispatcher = new LoggingOutboxEventDispatcher(
                calendarSyncJobRepository,
                bookingRepository,
                eventTypeRepository,
                calendarConnectionRepository,
                bookingNotificationService,
                invariantMonitor,
                "google",
                true);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .build()));
        when(calendarConnectionRepository.findByUserIdAndProviderAndStatus(
                eq(hostId),
                eq(com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType.GOOGLE),
                eq(com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus.ACTIVE)))
                .thenReturn(java.util.Optional.of(new CalendarConnection()));

        optionalDispatcher.dispatch(event);

        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("BOOKING"),
                eq(bookingId),
                eq("google"),
                eq("CREATE"),
                eq(null),
                eq(hostId), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatch_nonBooking_doesNotEnqueueSyncJob() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("User")
                .aggregateId(UUID.randomUUID())
                .eventType("USER_UPDATED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();

        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository, never()).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatch_bookingConfirmed_usesEventTypeAuthoritativeConnectionWhenPresent() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID authoritativeConnectionId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(bookingId)
                .eventType("BOOKING_CONFIRMED")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .build();
        when(bookingRepository.findAnyById(bookingId))
                .thenReturn(java.util.Optional.of(Booking.builder()
                        .id(bookingId)
                        .hostId(hostId)
                        .eventTypeId(eventTypeId)
                        .build()));
        EventType configured = EventType.builder()
                .id(eventTypeId)
                .userId(hostId)
                .name("Test")
                .slug("test")
                .duration(java.time.Duration.ofMinutes(30))
                .bufferBefore(java.time.Duration.ZERO)
                .bufferAfter(java.time.Duration.ZERO)
                .slotInterval(java.time.Duration.ofMinutes(30))
                .minNotice(java.time.Duration.ZERO)
                .maxAdvance(java.time.Duration.ofDays(30))
                .holdDuration(java.time.Duration.ofMinutes(5))
                .organizerCalendarConnectionId(authoritativeConnectionId)
                .build();
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, hostId))
                .thenReturn(java.util.Optional.of(configured));

        dispatcher.dispatch(event);

        verify(calendarSyncJobRepository).upsertPendingJob(
                org.mockito.ArgumentMatchers.any(UUID.class),
                eq("BOOKING"),
                eq(bookingId),
                eq("google"),
                eq("CREATE"),
                eq(null),
                eq(hostId),
                eq(authoritativeConnectionId));
    }
}
