package io.bunnycal.session.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class GroupHostNotificationDigestScheduler {

    private final GroupHostNotificationService notificationService;

    public GroupHostNotificationDigestScheduler(GroupHostNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${group.host-notifications.digest-poll-ms:300000}")
    @SchedulerLock(name = "group_host_notification_digest", lockAtMostFor = "PT10M")
    public void sendDueDigests() {
        notificationService.sendDueDigests();
    }
}
