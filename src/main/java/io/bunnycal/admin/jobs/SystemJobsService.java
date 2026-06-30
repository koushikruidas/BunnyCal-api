package io.bunnycal.admin.jobs;

import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.jobs.dto.AdminOutboxEventDto;
import io.bunnycal.admin.jobs.dto.AdminSyncDeadLetterDto;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.outbox.OutboxEventRepository;
import io.bunnycal.booking.outbox.OutboxEventStatus;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.sync.repository.CalendarSyncJobRepository;
import io.bunnycal.sync.service.SyncAdminService;
import io.bunnycal.sync.state.CalendarSyncJob;
import io.bunnycal.sync.state.SyncJobStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin browser over operational queues that actually exist in the current system:
 * outbox events and sync dead letters. There is no separate persisted email queue yet, so
 * the UI surfaces that honestly instead of inventing one.
 */
@Service
public class SystemJobsService {

    private static final String OUTBOX_TARGET_TYPE = "OUTBOX_EVENT";
    private static final String SYNC_TARGET_TYPE = "SYNC_JOB";
    private static final int MAX_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final CalendarSyncJobRepository syncJobRepository;
    private final SyncAdminService syncAdminService;
    private final AdminAuditService auditService;
    private final UserRepository userRepository;
    private final TimeSource timeSource;

    public SystemJobsService(OutboxEventRepository outboxEventRepository,
                             CalendarSyncJobRepository syncJobRepository,
                             SyncAdminService syncAdminService,
                             AdminAuditService auditService,
                             UserRepository userRepository,
                             TimeSource timeSource) {
        this.outboxEventRepository = outboxEventRepository;
        this.syncJobRepository = syncJobRepository;
        this.syncAdminService = syncAdminService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOutboxEventDto> searchOutbox(
            OutboxEventStatus status, String aggregateType, String eventType, int page, int size) {

        Specification<OutboxEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (aggregateType != null && !aggregateType.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("aggregateType")), aggregateType.trim().toLowerCase(Locale.ROOT)));
            }
            if (eventType != null && !eventType.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("eventType")),
                        "%" + eventType.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return PageResponse.of(
                outboxEventRepository.findAll(spec, page(page, size, "createdAt")),
                AdminOutboxEventDto::from);
    }

    @Transactional(readOnly = true)
    public List<AdminSyncDeadLetterDto> deadLetters(String provider, int limit) {
        return syncAdminService.deadLetters(provider, limit).stream()
                .map(v -> new AdminSyncDeadLetterDto(
                        v.id(),
                        v.provider(),
                        v.internalRefType(),
                        v.internalRefId(),
                        v.desiredAction(),
                        v.status(),
                        v.attemptCount(),
                        v.lastError(),
                        v.updatedAt()))
                .toList();
    }

    @Transactional
    public AdminOutboxEventDto retryOutbox(UUID adminId, UUID outboxEventId, String reason) {
        requireReason(reason);
        OutboxEvent event = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Outbox event not found."));
        if (event.getStatus() == OutboxEventStatus.PROCESSED) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION, "Processed outbox events cannot be retried.");
        }
        AdminOutboxEventDto before = AdminOutboxEventDto.from(event);
        event.setStatus(OutboxEventStatus.PENDING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(timeSource.now());
        event.setLastError(null);
        OutboxEvent saved = outboxEventRepository.save(event);
        audit(adminId, "OUTBOX_EVENT_RETRY", OUTBOX_TARGET_TYPE, saved.getId(), reason, before, AdminOutboxEventDto.from(saved));
        return AdminOutboxEventDto.from(saved);
    }

    @Transactional
    public AdminSyncDeadLetterDto requeueDeadLetter(UUID adminId, UUID syncJobId, String reason) {
        requireReason(reason);
        CalendarSyncJob beforeJob = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Sync job not found."));
        AdminSyncDeadLetterDto before = toDeadLetterDto(beforeJob);
        if (beforeJob.getStatus() != SyncJobStatus.FAILED) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION, "Only failed sync jobs can be requeued.");
        }
        boolean requeued = syncAdminService.requeue(syncJobId);
        if (!requeued) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION, "Sync job could not be requeued.");
        }
        CalendarSyncJob afterJob = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Sync job not found after requeue."));
        AdminSyncDeadLetterDto after = toDeadLetterDto(afterJob);
        audit(adminId, "SYNC_DEAD_LETTER_REQUEUE", SYNC_TARGET_TYPE, syncJobId, reason, before, after);
        return after;
    }

    private AdminSyncDeadLetterDto toDeadLetterDto(CalendarSyncJob job) {
        return new AdminSyncDeadLetterDto(
                job.getId(),
                job.getProvider(),
                job.getInternalRefType().name(),
                job.getInternalRefId(),
                job.getDesiredAction().name(),
                job.getStatus().name(),
                job.getAttemptCount(),
                job.getLastError(),
                job.getUpdatedAt());
    }

    private void audit(UUID adminId, String action, String targetType, UUID targetId,
                       String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(User::getEmail).orElse(null);
        auditService.record(adminId, email, action, targetType, targetId, reason, before, after);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "reason is required.");
        }
    }

    private static PageRequest page(int page, int size, String sortField) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, sortField));
    }
}
