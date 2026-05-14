package com.daedalussystems.easySchedule.booking.outbox;

import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.booking.notification.BookingNotificationService;
import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import com.daedalussystems.easySchedule.sync.invariants.CompositeSyncStateClassifier;
import com.daedalussystems.easySchedule.sync.invariants.LineageContext;
import com.daedalussystems.easySchedule.sync.invariants.SyncInvariantMonitor;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default dispatcher that logs the event and does nothing else.
 *
 * <p>Replace or extend with a real implementation once a downstream consumer
 * (notification service, calendar sync, etc.) is available.
 */
@Component
public class LoggingOutboxEventDispatcher implements OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventDispatcher.class);
    private static final String BOOKING_AGGREGATE = "Booking";

    private final CalendarSyncJobRepository calendarSyncJobRepository;
    private final BookingRepository bookingRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    @Nullable
    private final BookingNotificationService bookingNotificationService;
    private final SyncInvariantMonitor invariantMonitor;
    private final String provider;
    private final boolean providerOptionalPublicBookingEnabled;

    public LoggingOutboxEventDispatcher(CalendarSyncJobRepository calendarSyncJobRepository,
                                        BookingRepository bookingRepository,
                                        CalendarConnectionRepository calendarConnectionRepository,
                                        @Nullable BookingNotificationService bookingNotificationService,
                                        SyncInvariantMonitor invariantMonitor,
                                        @Value("${sync.provider.default:google}") String provider,
                                        @Value("${booking.public.provider-optional.enabled:false}")
                                        boolean providerOptionalPublicBookingEnabled) {
        this.calendarSyncJobRepository = calendarSyncJobRepository;
        this.bookingRepository = bookingRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.bookingNotificationService = bookingNotificationService;
        this.invariantMonitor = invariantMonitor;
        this.provider = provider;
        this.providerOptionalPublicBookingEnabled = providerOptionalPublicBookingEnabled;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        if (bookingNotificationService != null) {
            bookingNotificationService.handleOutboxEvent(event);
        }

        if (isBookingSyncCandidate(event)) {
            String desiredAction = mapDesiredAction(event.getEventType());
            if (desiredAction != null) {
                if (shouldSkipProviderSync(event)) {
                    log.info("outbox.sync_job_skipped_no_active_provider bookingId={} provider={} action={}",
                            event.getAggregateId(), provider, desiredAction);
                    return;
                }
                calendarSyncJobRepository.upsertPendingJob(
                        UUID.randomUUID(),
                        "BOOKING",
                        event.getAggregateId(),
                        provider,
                        desiredAction,
                        null
                );
                log.info("outbox.sync_job_created id={} bookingId={} provider={} action={}",
                        event.getId(), event.getAggregateId(), provider, desiredAction);
                emitSyncEnqueueInvariant(event, desiredAction);
                return;
            }
        }

        log.info("outbox.dispatch id={} type={} aggregateType={} aggregateId={}",
                event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId());
    }

    private static boolean isBookingSyncCandidate(OutboxEvent event) {
        return event != null
                && BOOKING_AGGREGATE.equals(event.getAggregateType())
                && event.getAggregateId() != null;
    }

    private boolean shouldSkipProviderSync(OutboxEvent event) {
        if (!providerOptionalPublicBookingEnabled) {
            return false;
        }
        CalendarProviderType providerType = parseProviderType(provider);
        if (providerType == null) {
            return false;
        }
        return bookingRepository.findAnyById(event.getAggregateId())
                .map(booking -> calendarConnectionRepository
                        .findByUserIdAndProviderAndStatus(booking.getHostId(), providerType, CalendarConnectionStatus.ACTIVE)
                        .isEmpty())
                .orElse(false);
    }

    private static CalendarProviderType parseProviderType(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            return CalendarProviderType.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String mapDesiredAction(String eventType) {
        if ("BOOKING_CONFIRMED".equals(eventType)) {
            return "CREATE";
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "UPDATE";
        }
        if ("BOOKING_CANCELLED".equals(eventType)) {
            return "DELETE";
        }
        return null;
    }

    private void emitSyncEnqueueInvariant(OutboxEvent event, String desiredAction) {
        BookingState bookingState = switch (desiredAction) {
            case "DELETE" -> BookingState.CANCELLED;
            case "UPDATE" -> BookingState.CONFIRMED;
            case "CREATE" -> BookingState.CONFIRMED;
            default -> BookingState.PENDING;
        };
        invariantMonitor.assertState(
                "sync_enqueue_transition",
                bookingState,
                SyncJobStatus.PENDING,
                bookingState == BookingState.CANCELLED
                        ? CompositeSyncStateClassifier.ProjectionLifecycle.TOMBSTONED_SOFT
                        : CompositeSyncStateClassifier.ProjectionLifecycle.ACTIVE,
                CompositeSyncStateClassifier.ParticipationLifecycle.NEEDS_ACTION,
                new LineageContext(
                        String.valueOf(event.getId()),
                        String.valueOf(event.getId()),
                        String.valueOf(event.getAggregateId()),
                        "",
                        "",
                        ""));
    }
}
