package io.bunnycal.session.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.domain.SessionStatus;
import io.bunnycal.session.dto.SessionDetailResponse;
import io.bunnycal.session.dto.SessionPageResponse;
import io.bunnycal.session.dto.SessionRegistrationPageResponse;
import io.bunnycal.session.dto.SessionRegistrationResponse;
import io.bunnycal.session.dto.SessionSummaryResponse;
import io.bunnycal.session.dto.SessionSyncStatusResponse;
import io.bunnycal.session.repository.EventSessionRepository;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.state.SyncJobStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionQueryService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final EventTypeRepository eventTypeRepository;
    private final EventSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final CalendarSyncJobRepository syncJobRepository;
    private final TimeSource timeSource;

    public SessionQueryService(EventTypeRepository eventTypeRepository,
                               EventSessionRepository sessionRepository,
                               SessionRegistrationRepository registrationRepository,
                               CalendarSyncJobRepository syncJobRepository,
                               TimeSource timeSource) {
        this.eventTypeRepository = eventTypeRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.syncJobRepository = syncJobRepository;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public SessionPageResponse listSessionsForHost(UUID requesterId,
                                                   UUID hostId,
                                                   UUID eventTypeId,
                                                   SessionStatus status,
                                                   Instant fromTime,
                                                   Instant toTime,
                                                   SyncJobStatus syncStatus,
                                                   String cursor,
                                                   Integer limit) {
        requireHostAccess(requesterId, hostId);
        return listSessions(hostId, eventTypeId, status, fromTime, toTime, syncStatus, cursor, limit, true);
    }

    @Transactional(readOnly = true)
    public SessionPageResponse listSessionsForEventType(UUID requesterId,
                                                        UUID eventTypeId,
                                                        SessionStatus status,
                                                        Instant fromTime,
                                                        Instant toTime,
                                                        SyncJobStatus syncStatus,
                                                        String cursor,
                                                        Integer limit) {
        EventType eventType = eventTypeRepository.findByIdAndUserIdAndDeletedAtIsNull(eventTypeId, requesterId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        return listSessions(eventType.getUserId(), eventTypeId, status, fromTime, toTime, syncStatus, cursor, limit, false);
    }

    @Transactional(readOnly = true)
    public SessionDetailResponse getSessionDetail(UUID requesterId, UUID sessionId) {
        return toDetail(requireSession(requesterId, sessionId));
    }

    @Transactional(readOnly = true)
    public SessionRegistrationPageResponse listRegistrations(UUID requesterId,
                                                             UUID sessionId,
                                                             String status,
                                                             String cursor,
                                                             Integer limit) {
        EventSessionRepository.SessionDetailRow session = requireSession(requesterId, sessionId);
        Cursor registrationCursor = decodeRegistrationCursor(cursor);
        int safeLimit = sanitizeLimit(limit);
        List<SessionRegistrationRepository.SessionRegistrationRow> rows = registrationRepository.findSessionRegistrations(
                requesterId,
                sessionId,
                normalizeRegistrationStatus(status),
                registrationCursor == null ? null : registrationCursor.timestamp(),
                registrationCursor == null ? null : registrationCursor.id(),
                timeSource.now(),
                safeLimit + 1);

        return new SessionRegistrationPageResponse(
                rows.stream().limit(safeLimit).map(this::toRegistration).toList(),
                nextRegistrationCursor(rows, safeLimit),
                rows.size() > safeLimit);
    }

    private SessionPageResponse listSessions(UUID hostId,
                                             UUID eventTypeId,
                                             SessionStatus status,
                                             Instant fromTime,
                                             Instant toTime,
                                             SyncJobStatus syncStatus,
                                             String cursor,
                                             Integer limit,
                                             boolean hasActiveParticipants) {
        Cursor sessionCursor = decodeSessionCursor(cursor);
        int safeLimit = sanitizeLimit(limit);
        List<EventSessionRepository.SessionSummaryRow> rows = sessionRepository.findSessionSummaries(
                hostId,
                eventTypeId,
                status == null ? null : status.name(),
                fromTime,
                toTime,
                syncStatus == null ? null : syncStatus.name(),
                sessionCursor == null ? null : sessionCursor.timestamp(),
                sessionCursor == null ? null : sessionCursor.id(),
                hasActiveParticipants,
                safeLimit + 1);

        return new SessionPageResponse(
                rows.stream().limit(safeLimit).map(this::toSummary).toList(),
                nextSessionCursor(rows, safeLimit),
                rows.size() > safeLimit);
    }

    private SessionDetailResponse toDetail(EventSessionRepository.SessionDetailRow row) {
        return new SessionDetailResponse(
                row.getSessionId(),
                row.getHostId(),
                row.getEventTypeId(),
                row.getEventTypeName(),
                row.getEventTypeSlug(),
                row.getStartTime(),
                row.getEndTime(),
                row.getStatus(),
                row.getCapacity(),
                row.getConfirmedCount(),
                row.getPendingCount(),
                row.getRegistrationCount(),
                row.getCancelledCount(),
                occupancyPercent(row.getConfirmedCount(), row.getCapacity()),
                row.getCalendarSequence(),
                row.getTerminalIntentEpoch(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                isPast(row.getEndTime()),
                toSyncStatus(row));
    }

    private SessionSummaryResponse toSummary(EventSessionRepository.SessionSummaryRow row) {
        return new SessionSummaryResponse(
                row.getSessionId(),
                row.getHostId(),
                row.getEventTypeId(),
                row.getEventTypeName(),
                row.getEventTypeSlug(),
                row.getStartTime(),
                row.getEndTime(),
                row.getStatus(),
                row.getCapacity(),
                row.getConfirmedCount(),
                row.getPendingCount(),
                row.getRegistrationCount(),
                row.getOccupancyPercent(),
                row.getCalendarSequence(),
                row.getTerminalIntentEpoch(),
                isPast(row.getEndTime()),
                toSyncStatus(row));
    }

    private SessionRegistrationResponse toRegistration(SessionRegistrationRepository.SessionRegistrationRow row) {
        return new SessionRegistrationResponse(
                row.getRegistrationId(),
                row.getSessionId(),
                row.getHostId(),
                row.getGuestEmail(),
                row.getGuestName(),
                row.getStatus(),
                row.getExpiresAt(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion() == null ? 0L : row.getVersion(),
                Boolean.TRUE.equals(row.getExpired()));
    }

    private SessionSyncStatusResponse toSyncStatus(EventSessionRepository.SessionSummaryRow row) {
        if (row.getSyncJobId() == null) {
            return SessionSyncStatusResponse.empty(false);
        }
        boolean stale = row.getOwnershipVersion() != null
                && row.getOwnershipVersion() != row.getCalendarSequence();
        return new SessionSyncStatusResponse(
                row.getSyncJobId(),
                row.getProvider(),
                row.getSyncStatus(),
                row.getDesiredAction(),
                row.getExternalEventId(),
                row.getProviderEventUrl(),
                row.getConferenceUrl(),
                row.getConferenceProvider(),
                row.getLastError(),
                row.getAttemptCount() == null ? 0 : row.getAttemptCount(),
                row.getNextRetryAt(),
                row.getOwnershipVersion() == null ? 0L : row.getOwnershipVersion(),
                row.getSyncUpdatedAt(),
                stale);
    }

    private void requireHostAccess(UUID requesterId, UUID hostId) {
        if (requesterId == null || hostId == null || !requesterId.equals(hostId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private EventSessionRepository.SessionDetailRow requireSession(UUID requesterId, UUID sessionId) {
        EventSessionRepository.SessionDetailRow row = sessionRepository.findSessionDetail(sessionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Session not found."));
        if (!requesterId.equals(row.getHostId())) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        return row;
    }

    private static int sanitizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static double occupancyPercent(int confirmedCount, int capacity) {
        if (capacity <= 0) {
            return 0d;
        }
        return (confirmedCount * 100d) / capacity;
    }

    private boolean isPast(Instant endTime) {
        return endTime != null && endTime.isBefore(timeSource.now());
    }

    private static String normalizeRegistrationStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase();
    }

    private static String nextSessionCursor(List<EventSessionRepository.SessionSummaryRow> rows, int limit) {
        if (rows.size() <= limit) {
            return null;
        }
        EventSessionRepository.SessionSummaryRow last = rows.get(limit - 1);
        return encodeCursor(last.getStartTime(), last.getSessionId());
    }

    private static String nextRegistrationCursor(List<SessionRegistrationRepository.SessionRegistrationRow> rows, int limit) {
        if (rows.size() <= limit) {
            return null;
        }
        SessionRegistrationRepository.SessionRegistrationRow last = rows.get(limit - 1);
        return encodeCursor(last.getCreatedAt(), last.getRegistrationId());
    }

    private static Cursor decodeSessionCursor(String cursor) {
        return decodeCursor(cursor);
    }

    private static Cursor decodeRegistrationCursor(String cursor) {
        return decodeCursor(cursor);
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("cursor");
            }
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (Exception ex) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Invalid cursor.");
        }
    }

    private static String encodeCursor(Instant timestamp, UUID id) {
        String raw = timestamp.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant timestamp, UUID id) {}
}
