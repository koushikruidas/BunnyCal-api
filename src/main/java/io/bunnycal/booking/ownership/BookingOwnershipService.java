package io.bunnycal.booking.ownership;

import io.bunnycal.availability.domain.EventType;
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

    public BookingOwnershipService(BookingOwnershipRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public BookingOwnership ensureOwnership(UUID bookingId, EventType eventType) {
        if (eventType == null
                || eventType.getProjectionProvider() == null
                || eventType.getProjectionConnectionId() == null
                || eventType.getProjectionCalendarId() == null
                || eventType.getProjectionCalendarId().isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Projection ownership is required for booking sync lifecycle.");
        }
        BookingOwnership existing = repository.findByBookingId(bookingId).orElse(null);
        if (existing != null) {
            if ("AMBIGUOUS".equals(existing.getOwnershipState())) {
                meterRegistry.counter("ambiguous_booking_ownership_total").increment();
            }
            validateUnchangedOwnership(existing, eventType, bookingId);
            return existing;
        }

        BookingOwnership ownership = new BookingOwnership();
        ownership.setBookingId(bookingId);
        ownership.setOrganizerAuthority("APPLICATION");
        ownership.setProjectionProvider(eventType.getProjectionProvider());
        ownership.setProjectionConnectionId(eventType.getProjectionConnectionId());
        ownership.setProjectionCalendarId(eventType.getProjectionCalendarId());
        ownership.setOwnershipVersion(1L);
        ownership.setOwnershipState("RESOLVED");
        ownership.setAmbiguityReason(null);
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

    private static void validateUnchangedOwnership(BookingOwnership existing, EventType eventType, UUID bookingId) {
        boolean changed = existing.getProjectionProvider() != eventType.getProjectionProvider()
                || !existing.getProjectionConnectionId().equals(eventType.getProjectionConnectionId())
                || !existing.getProjectionCalendarId().equals(eventType.getProjectionCalendarId());
        if (changed) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Booking ownership is immutable and does not match event type projection ownership for booking " + bookingId);
        }
    }
}
