package com.daedalussystems.easySchedule.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailDeliverabilityPolicyTest {

    private final EmailDeliverabilityPolicy policy = new EmailDeliverabilityPolicy("draft.local,internal.local");

    @Test
    void normalize_trimsAndLowercases() {
        assertEquals("guest@example.com", policy.normalize("  Guest@Example.COM "));
    }

    @Test
    void normalize_blankReturnsNull() {
        assertNull(policy.normalize("   "));
    }

    @Test
    void isSynthetic_detectsConfiguredDomain() {
        assertTrue(policy.isSynthetic("draft-123@draft.local"));
        assertTrue(policy.isSynthetic("admin@internal.local"));
    }

    @Test
    void isDeliverable_rejectsSyntheticAndMalformed() {
        assertFalse(policy.isDeliverable("draft-123@draft.local"));
        assertFalse(policy.isDeliverable("no-at-symbol"));
        assertFalse(policy.isDeliverable(" "));
    }

    @Test
    void isDeliverable_acceptsNormalEmail() {
        assertTrue(policy.isDeliverable("user@example.com"));
    }
}

