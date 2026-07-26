package io.bunnycal.payments.provider;

/**
 * Provider-agnostic gateway for SaaS subscription billing.
 *
 * <p>Composed of two responsibility-split halves — {@link BillingProviderReader} (safe, retryable
 * queries of current provider state) and {@link BillingProviderWriter} (operations that move money
 * or mutate provider state) — plus webhook verification, which is its own concern (signature
 * checking + normalization, not a remote operation). Callers that only read should depend on
 * {@link BillingProviderReader}; callers that only issue commands should depend on
 * {@link BillingProviderWriter}. This full interface exists for the single active provider bean and
 * for code that legitimately needs both halves.
 *
 * <p>Neutral value objects ({@link ProviderRequests} / {@link ProviderSnapshots}) keep provider SDK
 * types out of {@code io.bunnycal.billing}. A second provider (e.g. Razorpay) is added by
 * implementing this interface with no change to any billing business logic.
 *
 * <p>Implementations are gated by {@code billing.enabled}; when billing is disabled no
 * implementation bean is registered.
 */
public interface PaymentProvider extends BillingProviderReader, BillingProviderWriter {

    /**
     * Verifies the authenticity of an inbound webhook callback and normalizes it into a
     * neutral {@link ProviderWebhookEvent} (typed {@link BillingEventType} + pre-extracted
     * fields). Different providers sign with different headers (Stripe: {@code Stripe-Signature};
     * Dodo / Standard Webhooks: {@code webhook-id} / {@code webhook-signature} /
     * {@code webhook-timestamp}), so the full header map is passed and each implementation
     * reads what it needs.
     *
     * @param payload the raw request body bytes (must be the exact bytes received)
     * @param headers all inbound request headers (case-insensitive lookup expected)
     * @throws WebhookVerificationException if the signature is invalid
     */
    ProviderWebhookEvent verifyWebhook(byte[] payload, java.util.Map<String, String> headers);
}
