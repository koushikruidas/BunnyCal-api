package io.bunnycal.calendar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderAuthorityContractTest {

    @Test
    void googleUris_alwaysSuppressProviderLifecycleEmails() {
        assertTrue(HttpGoogleApiClient.CREATE_EVENT_URI_TEMPLATE.contains("sendUpdates=none"));
        assertTrue(HttpGoogleApiClient.UPDATE_EVENT_URI_TEMPLATE.contains("sendUpdates=none"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void microsoftPayload_alwaysSuppressesResponseRequested() throws Exception {
        Method m = HttpMicrosoftApiClient.class.getDeclaredMethod(
                "buildEventBody",
                String.class,
                String.class,
                Instant.class,
                Instant.class,
                String.class,
                String.class,
                String.class,
                ConferencingInstruction.class);
        m.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) m.invoke(
                null,
                "title",
                "desc",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                "host@example.com",
                "guest@example.com",
                "Guest",
                ConferencingInstruction.requestNativeMeet(ConferencingProviderType.MICROSOFT_TEAMS));
        assertEquals(Boolean.FALSE, body.get("responseRequested"));
    }
}
