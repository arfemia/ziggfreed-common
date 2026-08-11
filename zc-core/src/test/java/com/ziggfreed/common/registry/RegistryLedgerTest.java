package com.ziggfreed.common.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.registry.RegistryLedger.RegistrationInfo;

/**
 * The generalized ledger, asserted at its own package so the semantics are pinned independently of
 * any one registry built on it. The placement engine's own subclass keeps its existing coverage;
 * this file is the base contract every future registry inherits.
 */
class RegistryLedgerTest {

    @Test
    void anUnattributedRegistrationIsOwnedByTheUnattributedName() {
        RegistryLedger<String> ledger = new RegistryLedger<>();
        ledger.put("a", null, "value");

        assertEquals(RegistryLedger.UNATTRIBUTED, ledger.info().get("a").owner());
        assertEquals("value", ledger.get("a"));
    }

    @Test
    void idsAreNormalizedOnBothSidesOfEveryLookup() {
        RegistryLedger<String> ledger = new RegistryLedger<>();
        ledger.put("  YourMod:Feature  ", "modA", "value");

        assertEquals("value", ledger.get("yourmod:feature"));
        assertTrue(ledger.isRegistered("YOURMOD:FEATURE"));
        assertTrue(ledger.ids().contains("yourmod:feature"));
        assertEquals("yourmod:feature", RegistryLedger.normalize(" YourMod:Feature "));
    }

    @Test
    void reRegisteringTheSameInstanceKeepsTheExistingFailureHistory() {
        RegistryLedger<String> ledger = new RegistryLedger<>("test");
        String provider = "same-instance";
        ledger.put("a", "modA", provider);
        ledger.recordFailure("a", "boom");

        ledger.put("a", "modB", provider);

        RegistrationInfo info = ledger.info().get("a");
        assertEquals("modA", info.owner(), "an idempotent re-register must not re-attribute the id");
        assertEquals(1, info.failures(), "nor drop the failure history it accumulated");
    }

    @Test
    void aGenuineOverwriteReplacesTheValueAndTheOwner() {
        RegistryLedger<Object> ledger = new RegistryLedger<>();
        Object second = new Object();
        ledger.put("a", "modA", new Object());
        ledger.put("a", "modB", second);

        assertEquals("modB", ledger.info().get("a").owner());
        assertEquals(second, ledger.get("a"));
    }

    @Test
    void aBlankIdOrNullValueIsIgnoredRatherThanStored() {
        RegistryLedger<String> ledger = new RegistryLedger<>();
        ledger.put("   ", "modA", "value");
        ledger.put(null, "modA", "value");
        ledger.put("a", "modA", null);

        assertTrue(ledger.ids().isEmpty());
        assertNull(ledger.get("a"));
    }

    @Test
    void recordFailureCountsAndKeepsTheLatestMessageOnlyForARegisteredId() {
        RegistryLedger<String> ledger = new RegistryLedger<>();
        ledger.put("a", "modA", "value");
        ledger.recordFailure("a", "first");
        ledger.recordFailure("a", "second");
        ledger.recordFailure("nothing-here", "ignored");

        Map<String, RegistrationInfo> info = ledger.info();
        assertEquals(1, info.size());
        assertEquals(2, info.get("a").failures());
        assertEquals("second", info.get("a").lastFailure());
    }

    @Test
    void clearDropsEveryRegistration() {
        RegistryLedger<String> ledger = new RegistryLedger<>();
        ledger.put("a", "modA", "value");

        ledger.clear();

        assertTrue(ledger.ids().isEmpty());
        assertFalse(ledger.isRegistered("a"));
    }
}
