package io.bunnycal.calendar.service;

import io.bunnycal.calendar.client.CalendarClientException;
import io.bunnycal.calendar.client.CalendarProviderClient;
import io.bunnycal.calendar.client.CalendarProviderClientRegistry;
import io.bunnycal.calendar.domain.CalendarOperationStatus;
import io.bunnycal.calendar.domain.CalendarProviderOperation;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarProviderOperationRepository;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultCalendarService implements CalendarService {
    private static final Duration CREATING_STALE_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(DefaultCalendarService.class);

    private final CalendarProviderClientRegistry providerClientRegistry;
    private final CalendarProviderOperationRepository operationRepository;

    public DefaultCalendarService(CalendarProviderClientRegistry providerClientRegistry,
                                  CalendarProviderOperationRepository operationRepository) {
        this.providerClientRegistry = providerClientRegistry;
        this.operationRepository = operationRepository;
    }

    @Override
    @Transactional
    public CreateEventResult createEvent(CreateCalendarEventCommand command) {
        CalendarProviderType provider = CalendarProviderType.valueOf(command.provider().toUpperCase());

        java.util.Optional<CalendarProviderOperation> existing =
                operationRepository.findByProviderAndIdempotencyKey(provider, command.idempotencyKey());
        boolean newlyCreated = existing.isEmpty();
        CalendarProviderOperation op = existing.orElseGet(() -> insertCreating(provider, command));

        if (op.getStatus() == CalendarOperationStatus.COMPLETED && op.getExternalEventId() != null) {
            log.info("provider_create_retry_detected bookingId={} provider={} externalEventId={} idempotencyKey={} lifecycleOperation=create",
                    command.internalId(), command.provider(), op.getExternalEventId(), command.idempotencyKey());
            return CreateEventResult.success(op.getExternalEventId());
        }
        if (!newlyCreated && op.getStatus() == CalendarOperationStatus.CREATING) {
            if (!isStaleCreating(op)) {
                return CreateEventResult.retryable("IN_PROGRESS");
            }
            // Stale CREATING rows can happen after crash/interruption; take over.
            op.setLastAttemptAt(Instant.now());
            operationRepository.save(op);
        }

        op.setLastAttemptAt(Instant.now());
        operationRepository.save(op);

        CalendarProviderClient.CreateEventDetails created;
        try {
            CalendarProviderClient providerClient = providerClientRegistry.clientFor(provider);
            created = providerClient.createEvent(
                    command.internalId(),
                    command.provider(),
                    command.idempotencyKey(),
                    command.conferencingInstruction(),
                    command.schedulingConnectionId());
        } catch (CalendarClientException ex) {
            log.warn("calendar_create_provider_failure internalId={} provider={} idempotencyKey={} statusCode={}",
                    command.internalId(), command.provider(), command.idempotencyKey(), ex.getStatusCode(), ex);
            op.setStatus(CalendarOperationStatus.FAILED);
            String errorCode = classifyProviderError(ex);
            op.setLastError(errorCode);
            op.setLastAttemptAt(Instant.now());
            operationRepository.save(op);
            if (isRetryableError(errorCode)) {
                return CreateEventResult.retryable(errorCode);
            }
            return CreateEventResult.permanent(errorCode);
        } catch (RuntimeException ex) {
            log.warn("calendar_create_provider_runtime_failure internalId={} provider={} idempotencyKey={}",
                    command.internalId(), command.provider(), command.idempotencyKey(), ex);
            op.setStatus(CalendarOperationStatus.FAILED);
            op.setLastError("PROVIDER_DOWN");
            op.setLastAttemptAt(Instant.now());
            operationRepository.save(op);
            return CreateEventResult.retryable("PROVIDER_DOWN");
        }

        String externalId = created.externalEventId();
        op.setStatus(CalendarOperationStatus.COMPLETED);
        op.setExternalEventId(externalId);
        op.setLastError(null);
        op.setLastAttemptAt(Instant.now());
        operationRepository.save(op);
        log.info("projection_write_trace bookingId={} projectionProvider={} projectionConnectionId={} externalEventId={} organizerAuthority=APPLICATION conferencingSource={} syncJobId={} lifecycleOperation=create",
                command.internalId(),
                command.provider(),
                command.schedulingConnectionId(),
                externalId,
                command.conferencingInstruction() == null ? "none" : command.conferencingInstruction().providerType(),
                "",
                "CREATE");
        return CreateEventResult.success(externalId, created.providerEventUrl(), created.conferenceUrl());
    }

    @Override
    public String updateEvent(UpdateCalendarEventCommand command) {
        CalendarProviderClient providerClient = providerClientRegistry.clientFor(command.provider());
        String externalId = providerClient.updateEvent(
                command.internalId(),
                command.provider(),
                command.externalEventId(),
                command.idempotencyKey(),
                command.conferencingInstruction(),
                command.schedulingConnectionId());
        log.info("projection_write_trace bookingId={} projectionProvider={} projectionConnectionId={} externalEventId={} organizerAuthority=APPLICATION conferencingSource={} syncJobId={} lifecycleOperation=update",
                command.internalId(),
                command.provider(),
                command.schedulingConnectionId(),
                externalId,
                command.conferencingInstruction() == null ? "none" : command.conferencingInstruction().providerType(),
                "");
        return externalId;
    }

    @Override
    public void deleteEvent(DeleteCalendarEventCommand command) {
        CalendarProviderClient providerClient = providerClientRegistry.clientFor(command.provider());
        providerClient.deleteEvent(command.internalId(), command.provider(), command.externalEventId(),
                command.schedulingConnectionId());
        log.info("projection_write_trace bookingId={} projectionProvider={} projectionConnectionId={} externalEventId={} organizerAuthority=APPLICATION conferencingSource=none syncJobId={} lifecycleOperation=delete",
                command.internalId(),
                command.provider(),
                command.schedulingConnectionId(),
                command.externalEventId(),
                "");
    }

    @Override
    public ObserveEventResult observeEvent(ObserveEventCommand command) {
        try {
            CalendarProviderClient providerClient = providerClientRegistry.clientFor(command.provider());
            boolean exists = providerClient.eventExists(
                    command.internalId(),
                    command.provider(),
                    command.externalEventId());
            if (!exists) {
                return ObserveEventResult.missing();
            }
            if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
                boolean matches = providerClient.eventMatches(
                        command.internalId(),
                        command.provider(),
                        command.externalEventId(),
                        command.idempotencyKey());
                if (!matches) {
                    return ObserveEventResult.mismatch();
                }
            }
            return ObserveEventResult.exists();
        } catch (CalendarClientException ex) {
            String code = classifyProviderError(ex);
            if (isRetryableError(code)) {
                return ObserveEventResult.retryable(code);
            }
            return ObserveEventResult.permanent(code);
        } catch (RuntimeException ex) {
            return ObserveEventResult.retryable("PROVIDER_DOWN");
        }
    }

    private CalendarProviderOperation insertCreating(CalendarProviderType provider,
                                                     CreateCalendarEventCommand command) {
        Instant now = Instant.now();
        operationRepository.insertCreatingIfAbsent(
                java.util.UUID.randomUUID(),
                provider.name(),
                command.internalId(),
                command.idempotencyKey(),
                CalendarOperationStatus.CREATING.name(),
                now
        );
        return operationRepository
                .findByProviderAndIdempotencyKey(provider, command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("calendar provider operation row missing after upsert"));
    }

    private static boolean isStaleCreating(CalendarProviderOperation operation) {
        Instant heartbeat = operation.getLastAttemptAt();
        if (heartbeat == null) {
            heartbeat = operation.getCreatedAt();
        }
        if (heartbeat == null) {
            return true;
        }
        return heartbeat.isBefore(Instant.now().minus(CREATING_STALE_TIMEOUT));
    }

    private static String classifyProviderError(CalendarClientException ex) {
        int status = ex.getStatusCode();
        if (status == 429) return "RATE_LIMIT";
        if (status == 401) return "AUTH_EXPIRED";
        if (status == 403) return "AUTH_REVOKED";
        if (status == 400 || status == 404) return "INVALID_REQUEST";
        if (status >= 500) return "PROVIDER_DOWN";
        return "PROVIDER_ERROR";
    }

    private static boolean isRetryableError(String errorCode) {
        return "RATE_LIMIT".equals(errorCode)
                || "AUTH_EXPIRED".equals(errorCode)
                || "PROVIDER_DOWN".equals(errorCode)
                || "IN_PROGRESS".equals(errorCode);
    }
}
