package io.bunnycal.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls how the customer-facing billing document is presented, which depends on the legal
 * billing model rather than the SDK in use.
 *
 * <p>Bound from {@code billing.invoice-presentation.*}.
 *
 * <ul>
 *   <li>{@code direct-merchant} (Stripe): BunnyCal is the legal seller. The PDF is a real
 *       tax invoice titled "Invoice".</li>
 *   <li>{@code mor-record-only} (Dodo, Paddle, …): the Merchant of Record is the legal seller
 *       and issues the official receipt. Our PDF is an internal record titled "Payment Summary"
 *       that names the MoR and the provider receipt reference.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "billing.invoice-presentation")
public record InvoicePresentationProperties(
        Mode mode,
        String sellerName,
        /** Display name of the Merchant of Record (e.g. "Dodo Payments"), used in MoR mode. */
        String merchantOfRecordName) {

    public InvoicePresentationProperties {
        if (mode == null) {
            mode = Mode.DIRECT_MERCHANT;
        }
        if (sellerName == null || sellerName.isBlank()) {
            sellerName = "BunnyCal";
        }
    }

    public boolean isMerchantOfRecord() {
        return mode == Mode.MOR_RECORD_ONLY;
    }

    public enum Mode {
        DIRECT_MERCHANT,
        MOR_RECORD_ONLY;

        /** Lenient binding so {@code direct-merchant} / {@code mor-record-only} map correctly. */
        public static Mode from(String raw) {
            if (raw == null) {
                return DIRECT_MERCHANT;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "mor-record-only", "mor_record_only", "mor" -> MOR_RECORD_ONLY;
                default -> DIRECT_MERCHANT;
            };
        }
    }
}
