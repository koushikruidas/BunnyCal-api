package io.bunnycal.sync.orchestration;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.booking.repository.BookingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalUpdateConvergenceService {
    private static final Logger log = LoggerFactory.getLogger(ExternalUpdateConvergenceService.class);

    private final BookingRepository bookingRepository;
    private final SlotCacheVersionService slotCacheVersionService;
    private final MeterRegistry meterRegistry;

    public ExternalUpdateConvergenceService(BookingRepository bookingRepository,
                                            SlotCacheVersionService slotCacheVersionService,
                                            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.slotCacheVersionService = slotCacheVersionService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ConvergenceResult convergeProviderUpdate(UUID bookingId,
                                                    String provider,
                                                    String externalEventId,
                                                    Instant startsAt,
                                                    Instant endsAt,
                                                    String source) {
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
            metric(source, "invalid_window");
            log.warn("external_update_projection_noop bookingId={} provider={} source={} externalEventId={} reason=invalid_window startsAt={} endsAt={}",
                    bookingId, provider, source, externalEventId, startsAt, endsAt);
            return new ConvergenceResult(0, "invalid_window");
        }

        var state = bookingRepository.findWindowStateById(bookingId).orElse(null);
        if (state == null) {
            metric(source, "missing_booking");
            log.info("external_update_projection_noop bookingId={} provider={} source={} externalEventId={} reason=missing_booking",
                    bookingId, provider, source, externalEventId);
            return new ConvergenceResult(0, "missing_booking");
        }

        boolean terminal = isTerminal(state.getStatus());
        log.info("external_update_projection_guard bookingId={} hostId={} provider={} source={} externalEventId={} statusBefore={} terminalBefore={} startBefore={} endBefore={} startIncoming={} endIncoming={}",
                bookingId,
                state.getHostId(),
                provider,
                source,
                externalEventId,
                state.getStatus(),
                terminal,
                state.getStartTime(),
                state.getEndTime(),
                startsAt,
                endsAt);

        if (terminal) {
            metric(source, "already_terminal");
            log.info("external_update_projection_noop bookingId={} provider={} source={} externalEventId={} reason=already_terminal status={}",
                    bookingId, provider, source, externalEventId, state.getStatus());
            return new ConvergenceResult(0, "already_terminal");
        }
        if (!"CONFIRMED".equals(state.getStatus())) {
            metric(source, "non_confirmed");
            log.info("external_update_projection_noop bookingId={} provider={} source={} externalEventId={} reason=non_confirmed status={}",
                    bookingId, provider, source, externalEventId, state.getStatus());
            return new ConvergenceResult(0, "non_confirmed");
        }
        if (startsAt.equals(state.getStartTime()) && endsAt.equals(state.getEndTime())) {
            metric(source, "unchanged_window");
            log.info("external_update_projection_noop bookingId={} provider={} source={} externalEventId={} reason=unchanged_window",
                    bookingId, provider, source, externalEventId);
            return new ConvergenceResult(0, "unchanged_window");
        }

        int updated = bookingRepository.projectExternalActiveWindow(bookingId, startsAt, endsAt);
        if (updated == 0) {
            metric(source, "cas_miss_or_state_drift");
            var after = bookingRepository.findWindowStateByIdAndHostId(bookingId, state.getHostId()).orElse(null);
            log.info("external_update_projection_noop bookingId={} provider={} source={} externalEventId={} reason=cas_miss_or_state_drift statusAfter={} startAfter={} endAfter={}",
                    bookingId,
                    provider,
                    source,
                    externalEventId,
                    after == null ? "MISSING" : after.getStatus(),
                    after == null ? null : after.getStartTime(),
                    after == null ? null : after.getEndTime());
            return new ConvergenceResult(0, "cas_miss_or_state_drift");
        }

        slotCacheVersionService.bumpVersionAfterCommit(state.getHostId());
        meterRegistry.counter("sync.external_update_slot_invalidation.total", "source", safeSource(source)).increment();
        metric(source, "applied");
        log.info("external_update_projection_applied bookingId={} hostId={} provider={} source={} externalEventId={} rowsUpdated={} startAfter={} endAfter={}",
                bookingId, state.getHostId(), provider, source, externalEventId, updated, startsAt, endsAt);
        return new ConvergenceResult(updated, "applied");
    }

    private void metric(String source, String result) {
        meterRegistry.counter("sync.external_update_convergence.total",
                "source", safeSource(source),
                "result", result).increment();
    }

    private static String safeSource(String source) {
        if ("provider_projection".equals(source)) {
            return source;
        }
        return "other";
    }

    private static boolean isTerminal(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        try {
            return BookingState.fromStatus(status).isTerminal();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public record ConvergenceResult(int bookingRows, String result) {
    }
}
