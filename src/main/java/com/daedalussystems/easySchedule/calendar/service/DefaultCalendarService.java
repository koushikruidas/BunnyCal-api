package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.CalendarProviderClient;
import com.daedalussystems.easySchedule.calendar.domain.CalendarOperationStatus;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderOperation;
import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.calendar.repository.CalendarProviderOperationRepository;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DefaultCalendarService implements CalendarService {
    private static final Duration CREATING_STALE_TIMEOUT = Duration.ofMinutes(2);

    private final CalendarProviderClient providerClient;
    private final CalendarProviderOperationRepository operationRepository;

    public DefaultCalendarService(CalendarProviderClient providerClient,
                                  CalendarProviderOperationRepository operationRepository) {
        this.providerClient = providerClient;
        this.operationRepository = operationRepository;
    }

    @Override
    @Transactional
    public CreateEventResult createEvent(CreateCalendarEventCommand command) {
        CalendarProviderType provider = CalendarProviderType.valueOf(command.provider().toUpperCase());

        CalendarProviderOperation op = operationRepository
                .findByProviderAndIdempotencyKey(provider, command.idempotencyKey())
                .orElseGet(() -> insertCreating(provider, command));

        if (op.getStatus() == CalendarOperationStatus.COMPLETED && op.getExternalEventId() != null) {
            return CreateEventResult.success(op.getExternalEventId());
        }
        if (op.getStatus() == CalendarOperationStatus.CREATING && !isStaleCreating(op)) {
            return CreateEventResult.retryable("IN_PROGRESS");
        }

        try {
            String externalId = providerClient.createEvent(command.internalId(), command.provider(), command.idempotencyKey());
            op.setStatus(CalendarOperationStatus.COMPLETED);
            op.setExternalEventId(externalId);
            op.setLastError(null);
            op.setLastAttemptAt(Instant.now());
            operationRepository.save(op);
            return CreateEventResult.success(externalId);
        } catch (CalendarClientException ex) {
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
            op.setStatus(CalendarOperationStatus.FAILED);
            op.setLastError("PROVIDER_DOWN");
            op.setLastAttemptAt(Instant.now());
            operationRepository.save(op);
            return CreateEventResult.retryable("PROVIDER_DOWN");
        }
    }

    @Override
    public String updateEvent(UpdateCalendarEventCommand command) {
        return providerClient.createEvent(command.internalId(), command.provider(), command.idempotencyKey());
    }

    @Override
    public void deleteEvent(DeleteCalendarEventCommand command) {
        // no-op for current stub provider client
    }

    private CalendarProviderOperation insertCreating(CalendarProviderType provider,
                                                     CreateCalendarEventCommand command) {
        CalendarProviderOperation op = new CalendarProviderOperation();
        op.setProvider(provider);
        op.setConnectionId(command.internalId());
        op.setIdempotencyKey(command.idempotencyKey());
        op.setStatus(CalendarOperationStatus.CREATING);
        op.setLastAttemptAt(Instant.now());
        try {
            return operationRepository.save(op);
        } catch (DataIntegrityViolationException duplicate) {
            return operationRepository
                    .findByProviderAndIdempotencyKey(provider, command.idempotencyKey())
                    .orElseThrow(() -> duplicate);
        }
    }

    private static boolean isStaleCreating(CalendarProviderOperation operation) {
        Instant createdAt = operation.getCreatedAt();
        if (createdAt == null) {
            return true;
        }
        return createdAt.isBefore(Instant.now().minus(CREATING_STALE_TIMEOUT));
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
