package io.bunnycal.session.notification;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.domain.GroupHostNotificationMode;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.notification.NotificationRecipientResolver;
import io.bunnycal.common.email.BrandedMailSender;
import io.bunnycal.common.email.EmailTemplate;
import io.bunnycal.booking.notification.NotificationSendDedupService;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Plain-text host notifications for GROUP registration activity; attendee ICS mail stays separate. */
@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class GroupHostNotificationService {

    private static final Logger log = LoggerFactory.getLogger(GroupHostNotificationService.class);
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter
            .ofPattern("EEE, MMM d, yyyy 'at' h:mm a z", Locale.ENGLISH);

    private final BrandedMailSender brandedMailSender;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final EventSessionRepository sessionRepository;
    private final GroupHostNotificationDigestRepository digestRepository;
    private final NotificationRecipientResolver recipientResolver;
    private final NotificationSendDedupService dedupService;
    private final TimeSource timeSource;
    private final String fromAddress;
    private final String fromName;
    private final Duration digestDelay;

    public GroupHostNotificationService(
            BrandedMailSender brandedMailSender,
            UserRepository userRepository,
            EventTypeRepository eventTypeRepository,
            EventSessionRepository sessionRepository,
            GroupHostNotificationDigestRepository digestRepository,
            NotificationRecipientResolver recipientResolver,
            NotificationSendDedupService dedupService,
            TimeSource timeSource,
            @Value("${booking.notifications.from:no-reply@BunnyCal.local}") String fromAddress,
            @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}") String fromName,
            @Value("${group.host-notifications.digest-delay:PT24H}") Duration digestDelay) {
        this.brandedMailSender = brandedMailSender;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.sessionRepository = sessionRepository;
        this.digestRepository = digestRepository;
        this.recipientResolver = recipientResolver;
        this.dedupService = dedupService;
        this.timeSource = timeSource;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.digestDelay = digestDelay;
    }

    public void handleRegistrationConfirmed(OutboxEvent event, SessionOutboxPayload payload) {
        handleActivity(event, payload, "REGISTRATION_CONFIRMED");
    }

    public void handleRegistrationCancelled(OutboxEvent event, SessionOutboxPayload payload) {
        if (!payload.wasConfirmed()) {
            return;
        }
        handleActivity(event, payload, "REGISTRATION_CANCELLED");
    }

    private void handleActivity(OutboxEvent event, SessionOutboxPayload payload, String activityType) {
        if (event == null || event.getId() == null || payload == null || payload.hostId() == null) {
            return;
        }
        EventSession session = payload.sessionId() == null
                ? null
                : sessionRepository.findById(payload.sessionId()).orElse(null);
        UUID eventTypeId = payload.eventTypeId() != null
                ? payload.eventTypeId()
                : session == null ? null : session.getEventTypeId();
        if (eventTypeId == null) {
            return;
        }
        EventType eventType = eventTypeRepository.findById(eventTypeId).orElse(null);
        if (eventType == null || eventType.getKind() != EventKind.GROUP) {
            return;
        }
        GroupHostNotificationMode mode = eventType.getGroupHostNotificationMode() == null
                ? GroupHostNotificationMode.SMART_SUMMARY
                : eventType.getGroupHostNotificationMode();
        if (mode == GroupHostNotificationMode.NONE) {
            return;
        }

        // Legacy outbox rows do not contain either occupancy field. Treat capacity as the
        // presence marker so a real zero confirmed count is preserved for cancellation mail.
        boolean payloadHasOccupancy = payload.capacity() > 0;
        int capacity = payloadHasOccupancy
                ? payload.capacity()
                : session == null ? eventType.getCapacity() : session.getCapacity();
        int confirmedCount = payloadHasOccupancy
                ? payload.confirmedCount()
                : session == null ? 0 : session.getConfirmedCount();
        boolean confirmation = "REGISTRATION_CONFIRMED".equals(activityType);
        boolean important = confirmation
                ? confirmedCount == 1 || (capacity > 0 && confirmedCount >= capacity)
                : confirmedCount == 0 || (capacity > 0 && confirmedCount + 1 >= capacity);

        if (mode == GroupHostNotificationMode.DAILY_DIGEST
                || (mode == GroupHostNotificationMode.SMART_SUMMARY && !important)) {
            queueDigest(event, payload, eventType, activityType, confirmedCount, capacity);
            return;
        }
        if (mode == GroupHostNotificationMode.IMPORTANT_ONLY && !important) {
            return;
        }
        sendImmediate(event, payload, eventType, activityType, confirmedCount, capacity);
    }

    private void queueDigest(OutboxEvent event,
                             SessionOutboxPayload payload,
                             EventType eventType,
                             String activityType,
                             int confirmedCount,
                             int capacity) {
        if (payload.sessionId() == null || payload.startTime() == null || payload.endTime() == null) {
            return;
        }
        digestRepository.tryInsert(
                UUID.randomUUID(),
                event.getId(),
                payload.hostId(),
                eventType.getId(),
                payload.sessionId(),
                activityType,
                truncate(eventName(payload, eventType), 255),
                truncate(guestName(payload), 255),
                truncate(guestEmail(payload), 320),
                payload.startTime(),
                payload.endTime(),
                Math.max(0, confirmedCount),
                Math.max(0, capacity),
                timeSource.now().plus(digestDelay));
    }

    private void sendImmediate(OutboxEvent event,
                               SessionOutboxPayload payload,
                               EventType eventType,
                               String activityType,
                               int confirmedCount,
                               int capacity) {
        Optional<User> host = userRepository.findById(payload.hostId());
        Optional<String> recipient = host.flatMap(recipientResolver::resolveHostRecipient);
        if (recipient.isEmpty()) {
            log.info("group_host_notification_skipped eventId={} hostId={} reason=missing_recipient",
                    event.getId(), payload.hostId());
            return;
        }
        String eventName = eventName(payload, eventType);
        boolean confirmation = "REGISTRATION_CONFIRMED".equals(activityType);
        boolean full = confirmation && capacity > 0 && confirmedCount >= capacity;
        boolean first = confirmation && confirmedCount == 1;
        boolean empty = !confirmation && confirmedCount == 0;
        boolean reopened = !confirmation && capacity > 0 && confirmedCount + 1 >= capacity;

        String subject;
        if (full) subject = "Session full: " + eventName;
        else if (first) subject = "First registration: " + eventName;
        else if (empty) subject = "Session now empty: " + eventName;
        else if (reopened) subject = "Spot reopened: " + eventName;
        else subject = (confirmation ? "New registration: " : "Registration cancelled: ") + eventName;

        EmailTemplate template = brandedMailSender.template()
                .eyebrow(confirmation ? "New registration" : "Registration cancelled")
                .headline(subject)
                .paragraph(confirmation
                        ? "A guest registered for your group event."
                        : "A guest cancelled their group registration.")
                .detail("Event", eventName)
                .detail("Session", formatSessionTime(payload.startTime(), host.get().getTimezone()))
                .detail("Guest", displayGuest(payload))
                .detail("Occupancy", Math.max(0, confirmedCount) + " / " + Math.max(0, capacity))
                .footerReason("you're receiving this because you host this group event")
                .build();
        sendBrandedWithDedup(event.getId(), recipient.get(), "GROUP_HOST_" + activityType, subject, template);
    }

    /** Sends all due rolling daily digests. Called by the locked scheduler. */
    public void sendDueDigests() {
        Instant now = timeSource.now();
        List<GroupHostNotificationDigestEntry> due =
                digestRepository.findTop200BySentAtIsNullAndDigestAfterLessThanEqualOrderByDigestAfterAscCreatedAtAsc(now);
        Map<DigestKey, Boolean> keys = new LinkedHashMap<>();
        for (GroupHostNotificationDigestEntry entry : due) {
            keys.putIfAbsent(new DigestKey(entry.getHostId(), entry.getEventTypeId()), Boolean.TRUE);
        }
        for (DigestKey key : keys.keySet()) {
            sendDigest(key, now);
        }
    }

    private void sendDigest(DigestKey key, Instant sentAt) {
        List<GroupHostNotificationDigestEntry> entries = digestRepository
                .findByHostIdAndEventTypeIdAndSentAtIsNullOrderByCreatedAtAsc(key.hostId(), key.eventTypeId());
        if (entries.isEmpty()) return;
        Optional<User> host = userRepository.findById(key.hostId());
        Optional<String> recipient = host.flatMap(recipientResolver::resolveHostRecipient);
        if (recipient.isEmpty()) {
            return;
        }

        String eventName = entries.get(0).getEventName();
        String body = buildDigestBody(entries, host.get().getTimezone());
        String dedupType = "GROUP_HOST_DIGEST";
        // Key the batch by its newest included activity. If delivery succeeds but marking rows
        // sent crashes, a retry suppresses the same batch. If new activity arrived meanwhile,
        // its newer key creates a fresh batch rather than silently marking that activity sent.
        UUID batchId = entries.get(entries.size() - 1).getOutboxEventId();
        boolean claimed = dedupService.claim(batchId, recipient.get(), dedupType);
        if (claimed) {
            try {
                sendBranded(recipient.get(), "Group booking digest: " + eventName,
                        brandedMailSender.template()
                                .eyebrow("Daily digest")
                                .headline("Activity on " + eventName)
                                .paragraph("Here's what happened on your group event since the last digest.")
                                .preformatted(body)
                                .footerReason("you're receiving this because you host this group event")
                                .build());
            } catch (RuntimeException ex) {
                dedupService.release(batchId, recipient.get(), dedupType);
                throw ex;
            }
        }
        for (GroupHostNotificationDigestEntry entry : entries) {
            entry.setSentAt(sentAt);
        }
        digestRepository.saveAll(entries);
    }

    private String buildDigestBody(List<GroupHostNotificationDigestEntry> entries, String timezone) {
        Map<UUID, List<GroupHostNotificationDigestEntry>> bySession = new LinkedHashMap<>();
        for (GroupHostNotificationDigestEntry entry : entries) {
            bySession.computeIfAbsent(entry.getSessionId(), ignored -> new ArrayList<>()).add(entry);
        }
        StringBuilder body = new StringBuilder("Here is your group registration activity from the last day.\n");
        int detailsShown = 0;
        int totalActivities = entries.size();
        for (List<GroupHostNotificationDigestEntry> sessionEntries : bySession.values()) {
            GroupHostNotificationDigestEntry latest = sessionEntries.get(sessionEntries.size() - 1);
            long registrations = sessionEntries.stream()
                    .filter(e -> "REGISTRATION_CONFIRMED".equals(e.getActivityType())).count();
            long cancellations = sessionEntries.size() - registrations;
            body.append("\nSession: ").append(formatSessionTime(latest.getSessionStartTime(), timezone))
                    .append("\nActivity: ").append(registrations).append(" registration")
                    .append(registrations == 1 ? "" : "s")
                    .append(", ").append(cancellations).append(" cancellation")
                    .append(cancellations == 1 ? "" : "s")
                    .append("\nCurrent occupancy: ").append(latest.getConfirmedCount())
                    .append(" / ").append(latest.getCapacity()).append('\n');
            for (GroupHostNotificationDigestEntry entry : sessionEntries) {
                if (detailsShown >= 50) break;
                body.append(entry.getActivityType().equals("REGISTRATION_CONFIRMED") ? "+ " : "- ")
                        .append(displayGuest(entry.getGuestName(), entry.getGuestEmail())).append('\n');
                detailsShown++;
            }
        }
        if (totalActivities > detailsShown) {
            body.append("\n+").append(totalActivities - detailsShown)
                    .append(" more activities are available in your BunnyCal dashboard.\n");
        }
        return body.toString();
    }

    private void sendBrandedWithDedup(UUID outboxEventId,
                                      String recipient,
                                      String dedupType,
                                      String subject,
                                      EmailTemplate template) {
        boolean claimed = dedupService.claim(outboxEventId, recipient, dedupType);
        if (!claimed) return;
        try {
            sendBranded(recipient, subject, template);
        } catch (RuntimeException ex) {
            dedupService.release(outboxEventId, recipient, dedupType);
            throw ex;
        }
    }

    private void sendBranded(String recipient, String subject, EmailTemplate template) {
        try {
            brandedMailSender.send(fromAddress, fromName, recipient, subject, template);
        } catch (Exception ex) {
            throw new IllegalStateException("group host notification delivery failed", ex);
        }
    }

    private static String eventName(SessionOutboxPayload payload, EventType eventType) {
        if (payload.eventName() != null && !payload.eventName().isBlank()) return payload.eventName();
        return eventType.getName() == null || eventType.getName().isBlank() ? "Group event" : eventType.getName();
    }

    private static String guestName(SessionOutboxPayload payload) {
        return payload.newAttendeeName() != null ? payload.newAttendeeName() : payload.cancelledAttendeeName();
    }

    private static String guestEmail(SessionOutboxPayload payload) {
        return payload.newAttendeeEmail() != null ? payload.newAttendeeEmail() : payload.cancelledAttendeeEmail();
    }

    private static String displayGuest(SessionOutboxPayload payload) {
        return displayGuest(guestName(payload), guestEmail(payload));
    }

    private static String displayGuest(String name, String email) {
        if (name != null && !name.isBlank() && email != null && !email.isBlank()) return name + " <" + email + ">";
        if (name != null && !name.isBlank()) return name;
        return email == null || email.isBlank() ? "Guest" : email;
    }

    private static String formatSessionTime(Instant start, String timezone) {
        if (start == null) return "Unknown";
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (Exception ignored) {
            zone = ZoneId.of("UTC");
        }
        return SESSION_TIME.format(start.atZone(zone));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private record DigestKey(UUID hostId, UUID eventTypeId) {}
}
