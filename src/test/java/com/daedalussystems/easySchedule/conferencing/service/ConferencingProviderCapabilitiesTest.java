package com.daedalussystems.easySchedule.conferencing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daedalussystems.easySchedule.conferencing.service.ConferencingProviderCapabilities.LifecycleType;
import org.junit.jupiter.api.Test;

class ConferencingProviderCapabilitiesTest {

    @Test
    void standaloneClassifiesAsStandaloneAndAllowsDisconnect() {
        ConferencingProviderCapabilities c = ConferencingProviderCapabilities.standalone();
        assertEquals(LifecycleType.STANDALONE, c.lifecycleType());
        assertTrue(c.standaloneOAuth());
        assertTrue(c.standaloneDisconnect());
        assertNull(c.managedBy());
    }

    @Test
    void managedByClassifiesAsCapabilityAndCarriesParent() {
        ConferencingProviderCapabilities c = ConferencingProviderCapabilities.managedBy("google_calendar");
        assertEquals(LifecycleType.CAPABILITY, c.lifecycleType());
        assertFalse(c.standaloneOAuth());
        assertFalse(c.standaloneDisconnect());
        assertEquals("google_calendar", c.managedBy());
    }

    @Test
    void lifecycleTypeSerialisesToLowercaseExternalId() {
        assertEquals("standalone", LifecycleType.STANDALONE.externalId());
        assertEquals("capability", LifecycleType.CAPABILITY.externalId());
    }
}
