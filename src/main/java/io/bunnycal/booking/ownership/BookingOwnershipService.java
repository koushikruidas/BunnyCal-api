package io.bunnycal.booking.ownership;

import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.service.BookingSchedulingProjectionResolver;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingOwnershipService {
    private static final Logger log = LoggerFactory.getLogger(BookingOwnershipService.class);

    private final BookingOwnershipRepository repository;
    private final MeterRegistry meterRegistry;
    private final BookingSchedulingProjectionResolver projectionResolver;

    public BookingOwnershipService(BookingOwnershipRepository repository,
                                  MeterRegistry meterRegistry,
                                  BookingSchedulingProjectionResolver projectionResolver) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.projectionResolver = projectionResolver;
    }

    @Transactional
    public BookingOwnership ensureOwnership(Booking booking, EventType eventType) {
        if (eventType == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Projection ownership is required for booking sync lifecycle.");
        }
        // Ownership is a per-booking record of where the event was ACTUALLY written, and it is
        // written once. This method runs on every outbox dispatch for a booking, not just at
        // creation, so it must not re-derive ownership from the event type on the later passes:
        // an event type's calendar can now be changed, and re-resolving would make a cancel or a
        // reschedule of an already-written booking disagree with the calendar it actually lives on.
        // Existing bookings keep their calendar; only new bookings pick up a changed setting.
        BookingOwnership existing = repository.findByBookingId(booking.getId()).orElse(null);
        if (existing != null) {
            if ("AMBIGUOUS".equals(existing.getOwnershipState())) {
                meterRegistry.counter("ambiguous_booking_ownership_total").increment();
            }
            return existing;
        }
        return createOwnership(booking.getId(), projectionResolver.resolve(booking, eventType));
    }

    private BookingOwnership createOwnership(UUID bookingId,
                                             BookingSchedulingProjectionResolver.SchedulingProjection projection) {

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setOrganizerAuthority("APPLICATION");
        ownership.setProjectionProvider(projection == null ? null : projection.provider());
        ownership.setProjectionConnectionId(projection == null ? null : projection.connectionId());
        ownership.setProjectionCalendarId(projection == null ? null : projection.calendarId());
        ownership.setOwnershipVersion(1L);
        ownership.setOwnershipState(projection == null ? "AMBIGUOUS" : "RESOLVED");
        ownership.setAmbiguityReason(projection == null ? "MISSING_SCHEDULING_CONNECTION" : null);
        ownership.setCreatedAt(Instant.now());
        ownership.setUpdatedAt(Instant.now());
        BookingOwnership saved = repository.save(ownership);
        log.info("booking_ownership_created bookingId={} projectionProvider={} projectionConnectionId={} projectionCalendarId={} organizerAuthority=APPLICATION",
                bookingId, saved.getProjectionProvider(), saved.getProjectionConnectionId(), saved.getProjectionCalendarId());
        return saved;
    }

    @Transactional
    public void attachExternalEventId(UUID bookingId, String externalEventId) {
        attachExternalEventIdResult(bookingId, externalEventId);
    }

    @Transactional
    public LinkageAttachResult attachExternalEventIdResult(UUID bookingId, String externalEventId) {
        if (externalEventId == null || externalEventId.isBlank()) {
            return LinkageAttachResult.NOOP_EMPTY_EXTERNAL_EVENT;
        }
        BookingOwnership ownership = repository.findByBookingId(bookingId).orElse(null);
        if (ownership == null) {
            meterRegistry.counter("ownership_resolution_failures_total", "reason", "missing_booking_ownership").increment();
            return LinkageAttachResult.MISSING_OWNERSHIP;
        }
        if (ownership.getProviderExternalEventId() == null || ownership.getProviderExternalEventId().isBlank()) {
            ownership.setProviderExternalEventId(externalEventId);
            ownership.setOwnershipVersion(ownership.getOwnershipVersion() + 1L);
            ownership.setUpdatedAt(Instant.now());
            repository.save(ownership);
            log.info("ownership_version_advanced bookingId={} ownershipVersion={} provider={} projectionConnectionId={} externalEventId={}",
                    bookingId, ownership.getOwnershipVersion(), ownership.getProjectionProvider(), ownership.getProjectionConnectionId(), externalEventId);
            return LinkageAttachResult.LINKED;
        }
        if (ownership.getProviderExternalEventId().equals(externalEventId)) {
            return LinkageAttachResult.ALREADY_LINKED_SAME;
        }
        meterRegistry.counter("external_event_linkage_conflict_total").increment();
        log.warn("external_event_linkage_conflict bookingId={} provider={} projectionConnectionId={} existingExternalEventId={} attemptedExternalEventId={} ownershipVersion={}",
                bookingId,
                ownership.getProjectionProvider(),
                ownership.getProjectionConnectionId(),
                ownership.getProviderExternalEventId(),
                externalEventId,
                ownership.getOwnershipVersion());
        return LinkageAttachResult.CONFLICT;
    }

    @Transactional(readOnly = true)
    public BookingOwnership requireOwnership(UUID bookingId) {
        BookingOwnership ownership = repository.findByBookingId(bookingId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking ownership missing."));
        if ("AMBIGUOUS".equals(ownership.getOwnershipState())) {
            meterRegistry.counter("ambiguous_booking_ownership_total").increment();
        }
        return ownership;
    }

    @Transactional(readOnly = true)
    public long ambiguousOwnershipCount() {
        return repository.countByOwnershipState("AMBIGUOUS");
    }

    public enum LinkageAttachResult {
        LINKED,
        ALREADY_LINKED_SAME,
        CONFLICT,
        MISSING_OWNERSHIP,
        NOOP_EMPTY_EXTERNAL_EVENT
    }

}
