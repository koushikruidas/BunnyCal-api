package io.bunnycal.billing.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.outbox.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends SaaS billing emails. Driven by outbox events (aggregateType "Subscription" /
 * "Invoice"), routed here from {@code LoggingOutboxEventDispatcher}. Reuses the same
 * JavaMailSender (AWS SES) as booking notifications.
 *
 * <p>Gated by {@code billing.notifications.enabled}; when disabled the dispatcher logs and
 * skips. Throwing from {@link #handleOutboxEvent} signals a transient failure so the
 * outbox retries delivery.
 */
@Service
@ConditionalOnProperty(name = "billing.notifications.enabled", havingValue = "true")
public class BillingNotificationService {

    private static final Logger log = LoggerFactory.getLogger(BillingNotificationService.class);

    public static final String AGGREGATE_SUBSCRIPTION = "Subscription";
    public static final String AGGREGATE_INVOICE = "Invoice";

    // Event types published to the billing outbox.
    public static final String TRIAL_ENDING = "TRIAL_ENDING";
    public static final String SUBSCRIPTION_RENEWED = "SUBSCRIPTION_RENEWED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String SUBSCRIPTION_CANCELLED = "SUBSCRIPTION_CANCELLED";
    public static final String SUBSCRIPTION_EXPIRED = "SUBSCRIPTION_EXPIRED";
    public static final String INVOICE_GENERATED = "INVOICE_GENERATED";
    public static final String REFUND_ISSUED = "REFUND_ISSUED";

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String fromName;

    public BillingNotificationService(JavaMailSender mailSender,
                                      ObjectMapper objectMapper,
                                      @Value("${billing.notifications.from:billing@bunnycal.local}") String fromAddress,
                                      @Value("${billing.notifications.from-name:BunnyCal Billing}") String fromName) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public static boolean supportsAggregateType(String aggregateType) {
        return AGGREGATE_SUBSCRIPTION.equals(aggregateType) || AGGREGATE_INVOICE.equals(aggregateType);
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (event == null || !supportsAggregateType(event.getAggregateType())) {
            return;
        }
        Map<String, Object> p = parsePayload(event);
        if (p == null) {
            log.warn("billing_notification_skip_parse_failed eventId={} type={}", event.getId(), event.getEventType());
            return;
        }
        String to = str(p.get("recipientEmail"));
        if (to == null || to.isBlank()) {
            log.warn("billing_notification_skip_missing_recipient eventId={} type={}", event.getId(), event.getEventType());
            return;
        }

        try {
            send(to, buildSubject(event.getEventType(), p), buildBody(event.getEventType(), p));
            log.info("billing_notification_sent eventId={} type={} to={}", event.getId(), event.getEventType(), to);
        } catch (Exception ex) {
            log.warn("billing_notification_send_failed eventId={} type={} message={}",
                    event.getId(), event.getEventType(), ex.getMessage());
            throw new IllegalStateException("billing email delivery failed", ex);
        }
    }

    private void send(String to, String subject, String body) throws Exception {
        var message = mailSender.createMimeMessage();
        var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        if (fromName != null && !fromName.isBlank()) {
            helper.setFrom(fromAddress, fromName);
        } else {
            helper.setFrom(fromAddress);
        }
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        message.saveChanges();
        mailSender.send(message);
    }

    private static String buildSubject(String eventType, Map<String, Object> p) {
        return switch (eventType) {
            case TRIAL_ENDING -> "Your BunnyCal trial ends in " + str(p.get("daysLeft")) + " day(s)";
            case SUBSCRIPTION_RENEWED -> "Your BunnyCal subscription renewed";
            case PAYMENT_FAILED -> "Action needed: your BunnyCal payment failed";
            case SUBSCRIPTION_CANCELLED -> "Your BunnyCal subscription was cancelled";
            case SUBSCRIPTION_EXPIRED -> "Your BunnyCal subscription has expired";
            case INVOICE_GENERATED -> "Your BunnyCal invoice " + str(p.get("invoiceNumber"));
            case REFUND_ISSUED -> "Your BunnyCal refund has been processed";
            default -> "BunnyCal billing update";
        };
    }

    private static String buildBody(String eventType, Map<String, Object> p) {
        String name = str(p.get("recipientName"));
        String greeting = name != null && !name.isBlank() ? "Hi " + name + ",\n\n" : "Hi,\n\n";
        return greeting + switch (eventType) {
            case TRIAL_ENDING -> "Your free trial ends in " + str(p.get("daysLeft"))
                    + " day(s). Add a payment method to keep your BunnyCal subscription active.\n\n— BunnyCal";
            case SUBSCRIPTION_RENEWED -> "Your subscription renewed successfully. Thanks for using BunnyCal!\n\n— BunnyCal";
            case PAYMENT_FAILED -> "We couldn't process your latest payment. Please update your payment method "
                    + "to avoid losing access.\n\n— BunnyCal";
            case SUBSCRIPTION_CANCELLED -> "Your subscription has been cancelled. You'll retain access until the end "
                    + "of your current period.\n\n— BunnyCal";
            case SUBSCRIPTION_EXPIRED -> "Your subscription has expired. Resubscribe anytime to regain access.\n\n— BunnyCal";
            case INVOICE_GENERATED -> "A new invoice (" + str(p.get("invoiceNumber"))
                    + ") is available in your billing history.\n\n— BunnyCal";
            case REFUND_ISSUED -> "We've processed your refund. It may take a few business days to appear on your "
                    + "statement.\n\n— BunnyCal";
            default -> "There's an update to your BunnyCal billing.\n\n— BunnyCal";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(OutboxEvent event) {
        try {
            String raw = event.getPayload();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            Map<String, Object> envelope = objectMapper.readValue(raw, Map.class);
            Object data = envelope.get("payload");
            return data instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (Exception ex) {
            log.warn("billing_notification_payload_parse_error eventId={} message={}", event.getId(), ex.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
