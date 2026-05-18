package com.daedalussystems.easySchedule.sync.service;

import com.daedalussystems.easySchedule.sync.repository.CalendarSyncJobRepository;
import com.daedalussystems.easySchedule.sync.state.CalendarSyncJob;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncAdminService {
    private final CalendarSyncJobRepository repository;

    public SyncAdminService(CalendarSyncJobRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DeadLetterView> deadLetters(String provider, int limit) {
        String effectiveProvider = provider == null || provider.isBlank() ? "google" : provider;
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return repository.findDeadLetters(effectiveProvider, boundedLimit).stream()
                .map(job -> new DeadLetterView(
                        job.getId(),
                        job.getProvider(),
                        job.getInternalRefType().name(),
                        job.getInternalRefId(),
                        job.getDesiredAction().name(),
                        job.getStatus().name(),
                        job.getAttemptCount(),
                        job.getLastError(),
                        job.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public boolean requeue(UUID id) {
        return repository.requeueFailedById(id) > 0;
    }

    public record DeadLetterView(
            UUID id,
            String provider,
            String internalRefType,
            UUID internalRefId,
            String desiredAction,
            String status,
            int attemptCount,
            String lastError,
            Instant updatedAt) {}
}

