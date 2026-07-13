package io.bunnycal.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailDeliverabilityPolicyTest {

    private final EmailDeliverabilityPolicy policy = new EmailDeliverabilityPolicy();

    @Test
    void normalize_trimsAndLowercases() {
        assertEquals("guest@example.com", policy.normalize("  Guest@Example.COM "));
    }

    @Test
    void normalize_blankReturnsNull() {
        assertNull(policy.normalize("   "));
    }

    @Test
    void isDeliverable_rejectsMalformed() {
        assertFalse(policy.isDeliverable("no-at-symbol"));
        assertFalse(policy.isDeliverable(" "));
    }

    @Test
    void isDeliverable_acceptsNormalEmail() {
        assertTrue(policy.isDeliverable("user@example.com"));
    }
}
