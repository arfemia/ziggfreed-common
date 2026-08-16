package com.ziggfreed.common.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * The quiet variant drops the ledger's own warning and NOTHING else. A caller reaches for it
     * because its own report of the same swap is the better one, not because it wants the swap to
     * half-happen - so the value, the owner and the idempotence rule all still apply.
     */
    @Test
    void aQuietOverwriteStillReplacesTheValueAndTheOwner() {
        RegistryLedger<Object> ledger = new RegistryLedger<>("test");
        Object second = new Object();
        ledger.put("a", "modA", new Object());

        ledger.putQuietly("a", "modB", second);

        assertEquals("modB", ledger.info().get("a").owner());
        assertEquals(second, ledger.get("a"));
    }

    @Test
    void aQuietRegistrationOfTheSameInstanceIsStillIdempotent() {
        RegistryLedger<String> ledger = new RegistryLedger<>("test");
        String provider = "same-instance";
        ledger.putQuietly("a", "modA", provider);
        ledger.recordFailure("a", "boom");

        ledger.putQuietly("a", "modB", provider);

        RegistrationInfo info = ledger.info().get("a");
        assertEquals("modA", info.owner());
        assertEquals(1, info.failures());
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

    // ==================== putIfAbsent: the singular slot ====================

    @Test
    void theFirstClaimOnASingularSlotWinsAndALaterOneIsRefusedAndReported() {
        List<String> reported = new ArrayList<>();
        RegistryLedger<Object> ledger = new RegistryLedger<>("test", reported::add);
        Object first = new Object();
        Object second = new Object();

        assertTrue(ledger.putIfAbsent("seam", "modA", first));
        assertFalse(ledger.putIfAbsent("seam", "modB", second));

        assertEquals(first, ledger.get("seam"), "the holder keeps the slot");
        assertEquals("modA", ledger.info().get("seam").owner(), "and keeps the attribution");
        assertEquals(1, reported.size(), "the refusal is worth exactly one line");
        assertTrue(reported.get(0).contains("modA") && reported.get(0).contains("modB"),
                "and that line names both owners: " + reported.get(0));
    }

    @Test
    void reOfferingTheSameInstanceToASingularSlotIsSilent() {
        List<String> reported = new ArrayList<>();
        RegistryLedger<Object> ledger = new RegistryLedger<>("test", reported::add);
        Object only = new Object();

        assertTrue(ledger.putIfAbsent("seam", "modA", only));
        assertFalse(ledger.putIfAbsent("seam", "modA", only),
                "a mod re-running its own setup does not claim the slot a second time");

        assertTrue(reported.isEmpty(), "and nothing is reported: " + reported);
        assertEquals(only, ledger.get("seam"));
    }

    @Test
    void aRefusedSingularClaimIsReportedOnlyOnceHoweverOftenItRepeats() {
        List<String> reported = new ArrayList<>();
        RegistryLedger<Object> ledger = new RegistryLedger<>("test", reported::add);
        ledger.putIfAbsent("seam", "modA", new Object());

        ledger.putIfAbsent("seam", "modB", new Object());
        ledger.putIfAbsent("seam", "modC", new Object());

        assertEquals(1, reported.size(), "a flapping re-register can never spam the log");
    }

    @Test
    void aBlankIdOrNullValueClaimsNothing() {
        RegistryLedger<String> ledger = new RegistryLedger<>();

        assertFalse(ledger.putIfAbsent("  ", "modA", "value"));
        assertFalse(ledger.putIfAbsent("seam", "modA", null));

        assertTrue(ledger.ids().isEmpty());
    }
}
