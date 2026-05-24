package com.daedalussystems.easySchedule.calendar.auth;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies a Microsoft Graph mailbox identity into the capability tiers the
 * application reacts to. Two outputs are persisted on calendar_connections:
 *
 * <ul>
 *   <li>account_classification: PERSONAL_MSA / AAD_WORK_SCHOOL / UNKNOWN</li>
 *   <li>organizer_invite_delivery: PROVIDER_NATIVE / BACKEND_ICS_FALLBACK / UNKNOWN</li>
 * </ul>
 *
 * <p>Consumer Microsoft accounts (outlook.com / hotmail / live / msn) do not
 * trigger the Exchange invitation pipeline via Graph; the backend must emit an
 * ICS METHOD:REQUEST attachment instead. AAD / Exchange Online mailboxes do
 * trigger native invite mail and require no backend fallback.
 *
 * <p>This is a single source of truth used by both OAuth callback stamping and
 * post-CREATE stamping, so the two paths cannot drift.
 */
public final class MicrosoftAccountClassifier {

    public static final String ACCOUNT_PERSONAL_MSA = "PERSONAL_MSA";
    public static final String ACCOUNT_AAD_WORK_SCHOOL = "AAD_WORK_SCHOOL";
    public static final String ACCOUNT_UNKNOWN = "UNKNOWN";

    public static final String DELIVERY_PROVIDER_NATIVE = "PROVIDER_NATIVE";
    public static final String DELIVERY_BACKEND_ICS_FALLBACK = "BACKEND_ICS_FALLBACK";
    public static final String DELIVERY_UNKNOWN = "UNKNOWN";

    // Domains owned by consumer Microsoft Account / Outlook.com infrastructure.
    // Graph events created under these mailboxes do NOT trigger the Exchange
    // organizer-invite mail pipeline. Source: empirically verified + Microsoft's
    // documented MSA tenant id (9188040d-6c67-4c5b-b112-36a304b66dad).
    private static final Set<String> MSA_DOMAINS = Set.of(
            "outlook.com",
            "hotmail.com",
            "live.com",
            "msn.com",
            "outlook.jp",
            "outlook.fr",
            "outlook.de",
            "hotmail.co.uk",
            "hotmail.fr",
            "live.co.uk",
            "live.fr",
            "live.de",
            "passport.com",
            "windowslive.com");

    private MicrosoftAccountClassifier() {}

    /**
     * Result of classifying a Microsoft mailbox. Both string fields are
     * non-null. Use the constants on {@link MicrosoftAccountClassifier} for
     * comparisons.
     */
    public record Classification(String accountClassification, String organizerInviteDelivery) {}

    /**
     * Classify by a single email-style identifier (userPrincipalName, mail,
     * preferred_username, or organizer.emailAddress.address — all are accepted).
     */
    public static Classification classifyByEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.contains("@")) {
            return new Classification(ACCOUNT_UNKNOWN, DELIVERY_UNKNOWN);
        }
        String domain = normalized.substring(normalized.indexOf('@') + 1);
        if (MSA_DOMAINS.contains(domain)) {
            return new Classification(ACCOUNT_PERSONAL_MSA, DELIVERY_BACKEND_ICS_FALLBACK);
        }
        return new Classification(ACCOUNT_AAD_WORK_SCHOOL, DELIVERY_PROVIDER_NATIVE);
    }
}
