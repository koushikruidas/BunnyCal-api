package io.bunnycal.calendar.domain;

import java.util.regex.Pattern;

/**
 * Classifies a Microsoft calendar connection's account type from its
 * {@code provider_user_id} (the {@code /me/id} value returned by Graph).
 *
 * <p>Discriminator:
 * <ul>
 *   <li><b>Consumer MSA</b> (outlook.com / hotmail.com / live.com): {@code /me/id}
 *       is a 16-hex-char puid, e.g. {@code ed9adb1ac97c0819}. These accounts
 *       <em>do not</em> have a Teams-for-Business license; passing
 *       {@code isOnlineMeeting=true, onlineMeetingProvider=teamsForBusiness} to
 *       Graph silently no-ops (event lands without an {@code onlineMeeting.joinUrl}).
 *       They also <em>do not</em> dispatch organizer invite mail (see
 *       {@code project_microsoft_msa_invite_asymmetry.md} in memory).</li>
 *   <li><b>Work/school (Entra)</b>: {@code /me/id} is an Entra {@code oid}
 *       UUID, e.g. {@code 12345678-1234-1234-1234-123456789012}. Teams-for-Business
 *       provisioning works; Graph dispatches invite mail.</li>
 * </ul>
 */
public final class MicrosoftAccountClassifier {

    private static final Pattern CONSUMER_PUID = Pattern.compile("^[0-9a-fA-F]{16}$");
    private static final Pattern ENTRA_OID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private MicrosoftAccountClassifier() {}

    public static boolean isConsumerMsa(CalendarConnection connection) {
        if (connection == null || connection.getProvider() != CalendarProviderType.MICROSOFT) {
            return false;
        }
        return isConsumerMsa(connection.getProviderUserId());
    }

    public static boolean isConsumerMsa(String providerUserId) {
        if (providerUserId == null) return false;
        String trimmed = providerUserId.trim();
        // A consumer puid is strictly 16 hex chars. An Entra oid is UUID-shaped.
        // Anything else (legacy, malformed) is treated as non-consumer — be
        // conservative: only block Teams when we're certain it's a consumer MSA.
        return CONSUMER_PUID.matcher(trimmed).matches() && !ENTRA_OID.matcher(trimmed).matches();
    }
}
