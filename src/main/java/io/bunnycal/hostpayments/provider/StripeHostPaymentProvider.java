package io.bunnycal.hostpayments.provider;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "${commerce.enabled:false} && '${commerce.stripe.secret-key:}' != ''")
public class StripeHostPaymentProvider implements HostPaymentProvider {
    private final HostCommerceProperties properties;
    private final RequestOptions platformOptions;

    public StripeHostPaymentProvider(HostCommerceProperties properties) {
        this.properties = properties;
        if (properties.stripe() == null) {
            throw new IllegalStateException("commerce.stripe configuration is required when host commerce is enabled");
        }
        requireConfigured(properties.stripe().secretKey(), "COMMERCE_STRIPE_SECRET_KEY");
        requireConfigured(properties.stripe().publishableKey(), "COMMERCE_STRIPE_PUBLISHABLE_KEY");
        requireConfigured(properties.stripe().webhookSecret(), "COMMERCE_STRIPE_WEBHOOK_SECRET");
        requireConfigured(properties.stripe().onboardingReturnUrl(), "COMMERCE_STRIPE_ONBOARDING_RETURN_URL");
        requireConfigured(properties.stripe().onboardingRefreshUrl(), "COMMERCE_STRIPE_ONBOARDING_REFRESH_URL");
        this.platformOptions = RequestOptions.builder()
                .setApiKey(properties.stripe().secretKey())
                .build();
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.STRIPE;
    }

    @Override
    public ConnectedAccount createConnectedAccount(java.util.UUID userId, String email, String name) {
        try {
            Account account = Account.create(AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.STANDARD)
                    .setCountry("US")
                    .setEmail(email)
                    .putMetadata("bunnycal_user_id", userId.toString())
                    .build(), platformOptions);
            return toConnectedAccount(account);
        } catch (StripeException e) {
            throw new HostPaymentProviderException("createConnectedAccount", e);
        }
    }

    @Override
    public String createOnboardingLink(String providerAccountId) {
        try {
            AccountLink link = AccountLink.create(AccountLinkCreateParams.builder()
                    .setAccount(providerAccountId)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setReturnUrl(properties.stripe().onboardingReturnUrl())
                    .setRefreshUrl(properties.stripe().onboardingRefreshUrl())
                    .build(), platformOptions);
            return link.getUrl();
        } catch (StripeException e) {
            throw new HostPaymentProviderException("createOnboardingLink", e);
        }
    }

    @Override
    public ConnectedAccount retrieveConnectedAccount(String providerAccountId) {
        try {
            return toConnectedAccount(Account.retrieve(providerAccountId, platformOptions));
        } catch (StripeException e) {
            throw new HostPaymentProviderException("retrieveConnectedAccount", e);
        }
    }

    @Override
    public CreatedPayment createPayment(CreatePayment request) {
        try {
            RequestOptions connected = connectedOptions(request.providerAccountId(), request.idempotencyKey());
            PaymentIntent intent = PaymentIntent.create(PaymentIntentCreateParams.builder()
                    .setAmount(request.amountMinor())
                    .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                    .setDescription(request.description())
                    .setReceiptEmail(request.receiptEmail())
                    .addPaymentMethodType("card")
                    .putMetadata("bunnycal_payment_id", request.paymentId().toString())
                    .putMetadata("reservation_id", request.reservationId().toString())
                    .putMetadata("reservation_kind", request.reservationKind())
                    .build(), connected);
            return new CreatedPayment(intent.getId(), intent.getClientSecret(), mapStatus(intent.getStatus()));
        } catch (StripeException e) {
            throw new HostPaymentProviderException("createPayment", e);
        }
    }

    @Override
    public ProviderPayment retrievePayment(String providerAccountId, String providerPaymentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(providerPaymentId, connectedOptions(providerAccountId, null));
            String chargeId = intent.getLatestCharge();
            return new ProviderPayment(intent.getId(), intent.getClientSecret(), null, chargeId, mapStatus(intent.getStatus()),
                    intent.getAmount(), intent.getCurrency().toUpperCase(Locale.ROOT), intent.getMetadata());
        } catch (StripeException e) {
            throw new HostPaymentProviderException("retrievePayment", e);
        }
    }

    @Override
    public void cancelPayment(String providerAccountId, String providerPaymentId) {
        try {
            PaymentIntent.retrieve(providerPaymentId, connectedOptions(providerAccountId, null))
                    .cancel(PaymentIntentCancelParams.builder().build(), connectedOptions(providerAccountId, null));
        } catch (StripeException e) {
            throw new HostPaymentProviderException("cancelPayment", e);
        }
    }

    @Override
    public void refundPayment(String providerAccountId, String providerPaymentId, String idempotencyKey) {
        try {
            Refund.create(RefundCreateParams.builder().setPaymentIntent(providerPaymentId).build(),
                    connectedOptions(providerAccountId, idempotencyKey));
        } catch (StripeException e) {
            throw new HostPaymentProviderException("refundPayment", e);
        }
    }

    @Override
    public VerifiedWebhook verifyWebhook(byte[] payload, java.util.Map<String, String> headers) {
        try {
            String raw = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            String signature = headers.entrySet().stream()
                    .filter(entry -> "stripe-signature".equalsIgnoreCase(entry.getKey()))
                    .map(java.util.Map.Entry::getValue).findFirst().orElse("");
            Event event = Webhook.constructEvent(raw, signature, properties.stripe().webhookSecret());
            String paymentIntentId = null;
            Long amountRefunded = null;
            Long chargeAmount = null;
            var object = event.getDataObjectDeserializer().getObject().orElse(null);
            if (object instanceof PaymentIntent intent) paymentIntentId = intent.getId();
            if (object instanceof com.stripe.model.Charge charge) {
                paymentIntentId = charge.getPaymentIntent();
                amountRefunded = charge.getAmountRefunded();
                chargeAmount = charge.getAmount();
            }
            if (object instanceof Refund refund) paymentIntentId = refund.getPaymentIntent();
            if (object instanceof com.stripe.model.Dispute dispute) paymentIntentId = dispute.getPaymentIntent();
            return new VerifiedWebhook(event.getId(), event.getType(), event.getAccount(), paymentIntentId,
                    amountRefunded, chargeAmount, raw);
        } catch (SignatureVerificationException e) {
            throw new HostPaymentProviderException("verifyWebhook", e);
        }
    }

    @Override
    public String publicClientKey() { return properties.stripe().publishableKey(); }

    private RequestOptions connectedOptions(String accountId, String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
                .setApiKey(properties.stripe().secretKey())
                .setStripeAccount(accountId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) builder.setIdempotencyKey(idempotencyKey);
        return builder.build();
    }

    private static void requireConfigured(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " is required when host commerce is enabled");
        }
    }

    private static ConnectedAccount toConnectedAccount(Account account) {
        String reason = null;
        if (account.getRequirements() != null) reason = account.getRequirements().getDisabledReason();
        return new ConnectedAccount(account.getId(), Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()), Boolean.TRUE.equals(account.getDetailsSubmitted()), reason);
    }

    private static ProviderPaymentStatus mapStatus(String status) {
        if ("succeeded".equals(status)) return ProviderPaymentStatus.SUCCEEDED;
        if ("processing".equals(status)) return ProviderPaymentStatus.PROCESSING;
        if ("canceled".equals(status)) return ProviderPaymentStatus.CANCELLED;
        if ("requires_payment_method".equals(status)) return ProviderPaymentStatus.FAILED;
        return ProviderPaymentStatus.REQUIRES_ACTION;
    }
}
