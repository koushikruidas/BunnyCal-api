package io.bunnycal.billing.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.common.email.BrandedMailSender;
import io.bunnycal.common.email.EmailTemplate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final BrandedMailSender brandedMailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String fromName;
    private final String appBaseUrl;

    public BillingNotificationService(BrandedMailSender brandedMailSender,
                                      ObjectMapper objectMapper,
                                      @Value("${billing.notifications.from:billing@bunnycal.local}") String fromAddress,
                                      @Value("${billing.notifications.from-name:BunnyCal Billing}") String fromName,
                                      @Value("${app.public-base-url:}") String appBaseUrl) {
        this.brandedMailSender = brandedMailSender;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.appBaseUrl = appBaseUrl;
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
            send(to, buildSubject(event.getEventType(), p), buildTemplate(event.getEventType(), p));
            log.info("billing_notification_sent eventId={} type={} to={}", event.getId(), event.getEventType(), to);
        } catch (Exception ex) {
            log.warn("billing_notification_send_failed eventId={} type={} message={}",
                    event.getId(), event.getEventType(), ex.getMessage());
            throw new IllegalStateException("billing email delivery failed", ex);
        }
    }

    private void send(String to, String subject, EmailTemplate template) throws Exception {
        brandedMailSender.send(fromAddress, fromName, to, subject, template);
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

    private EmailTemplate buildTemplate(String eventType, Map<String, Object> p) {
        String name = str(p.get("recipientName"));
        String greeting = name != null && !name.isBlank() ? "Hi " + name + "," : "Hi,";
        String billingUrl = appBaseUrl == null || appBaseUrl.isBlank()
                ? null
                : trimTrailingSlash(appBaseUrl) + "/dashboard/billing";

        EmailTemplate.Builder b = brandedMailSender.template()
                .eyebrow("Billing")
                .greeting(greeting)
                .footerReason("you're receiving this because it affects your BunnyCal subscription");

        switch (eventType) {
            case TRIAL_ENDING -> {
                String days = str(p.get("daysLeft"));
                b.headline("Your trial ends in " + days + " day(s)")
                 .paragraph("Your free trial ends in **" + days + " day(s)**. Add a payment method "
                         + "to keep your BunnyCal subscription active.")
                 .detail("Days left", days);
                if (billingUrl != null) b.primaryAction("Add payment method", billingUrl);
            }
            case SUBSCRIPTION_RENEWED -> {
                b.headline("Your subscription renewed")
                 .paragraph("Your subscription renewed successfully. Thanks for using BunnyCal!");
                addIfPresent(b, "Plan", str(p.get("planName")));
                addIfPresent(b, "Amount", str(p.get("amount")));
                if (billingUrl != null) b.primaryAction("View billing", billingUrl);
            }
            case PAYMENT_FAILED -> {
                b.headline("Your payment didn't go through")
                 .paragraph("We couldn't process your latest payment. Update your payment method "
                         + "to avoid losing access to your BunnyCal workspace.");
                if (billingUrl != null) b.primaryAction("Update payment method", billingUrl);
                b.note("Access continues for now — we'll retry automatically before anything changes.");
            }
            case SUBSCRIPTION_CANCELLED -> {
                b.headline("Your subscription was cancelled")
                 .paragraph("Your subscription has been cancelled. You'll retain access until the end "
                         + "of your current billing period.");
                addIfPresent(b, "Access until", str(p.get("periodEnd")));
                if (billingUrl != null) b.primaryAction("Resubscribe", billingUrl);
            }
            case SUBSCRIPTION_EXPIRED -> {
                b.headline("Your subscription has expired")
                 .paragraph("Your subscription has expired. Resubscribe anytime to regain access "
                         + "to your BunnyCal workspace.");
                if (billingUrl != null) b.primaryAction("Resubscribe", billingUrl);
            }
            case INVOICE_GENERATED -> {
                String invoice = str(p.get("invoiceNumber"));
                b.headline("Your invoice is ready")
                 .paragraph("A new invoice (**" + invoice + "**) is available in your billing history.")
                 .detail("Invoice", invoice);
                addIfPresent(b, "Amount", str(p.get("amount")));
                if (billingUrl != null) b.primaryAction("View invoice", billingUrl);
            }
            case REFUND_ISSUED -> {
                b.headline("Your refund has been processed")
                 .paragraph("We've processed your refund. It may take a few business days to appear "
                         + "on your statement.");
                addIfPresent(b, "Amount", str(p.get("amount")));
                if (billingUrl != null) b.primaryAction("View billing", billingUrl);
            }
            default -> {
                b.headline("An update to your billing")
                 .paragraph("There's an update to your BunnyCal billing.");
                if (billingUrl != null) b.primaryAction("View billing", billingUrl);
            }
        }
        return b.build();
    }

    private static void addIfPresent(EmailTemplate.Builder b, String label, String value) {
        if (value != null && !value.isBlank()) b.detail(label, value);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
