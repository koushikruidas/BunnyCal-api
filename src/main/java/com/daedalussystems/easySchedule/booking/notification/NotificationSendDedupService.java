package com.daedalussystems.easySchedule.booking.notification;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationSendDedupService {
    private final NotificationSendDedupRepository repository;
    private final TransactionTemplate requiresNew;

    public NotificationSendDedupService(NotificationSendDedupRepository repository,
                                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean claim(UUID outboxEventId, String recipientEmail, String eventType) {
        Integer inserted = requiresNew.execute(status -> repository.tryInsert(
                UUID.randomUUID(),
                outboxEventId,
                recipientEmail,
                eventType
        ));
        return inserted != null && inserted > 0;
    }

    public void release(UUID outboxEventId, String recipientEmail, String eventType) {
        requiresNew.executeWithoutResult(status ->
                repository.deleteClaim(outboxEventId, recipientEmail, eventType));
    }
}
