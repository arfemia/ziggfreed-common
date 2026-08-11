package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

// The canonical name: PlacementRegistryLedger INHERITS this record from the shared
// RegistryLedger, and an import (unlike a qualified reference) must name the declaring class.
import com.ziggfreed.common.registry.RegistryLedger.RegistrationInfo;

/**
 * The bookkeeping engine every open placement registry delegates to: identity (not equality)
 * decides an overwrite warning, a failure is only countable against something registered, and
 * {@link PlacementRegistryLedger#info()} is the one snapshot an admin channels-list command reads.
 */
class PlacementRegistryLedgerTest {

    @Test
    void unregisteredIdResolvesToNullAndIsNotRegistered() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        assertNull(ledger.get("nothing"));
        assertFalse(ledger.isRegistered("nothing"));
        assertTrue(ledger.ids().isEmpty());
        assertTrue(ledger.info().isEmpty());
    }

    @Test
    void putStoresTheValueAttributedToItsOwner() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        ledger.put("yourmod:npc_id", "yourmod", "handler-a");

        assertEquals("handler-a", ledger.get("yourmod:npc_id"));
        assertTrue(ledger.isRegistered("YourMod:NPC_ID"), "ids match case-insensitively");
        RegistrationInfo info = ledger.info().get("yourmod:npc_id");
        assertEquals("yourmod", info.owner());
        assertEquals(0, info.failures());
        assertNull(info.lastFailure());
    }

    @Test
    void aBlankOrNullOwnerIsAttributedAsUnattributed() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        ledger.put("a", null, "value");
        ledger.put("b", "   ", "value");

        assertEquals(PlacementRegistryLedger.UNATTRIBUTED, ledger.info().get("a").owner());
        assertEquals(PlacementRegistryLedger.UNATTRIBUTED, ledger.info().get("b").owner());
    }

    @Test
    void aBlankIdOrNullValueIsIgnored() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        ledger.put(null, "owner", "value");
        ledger.put("", "owner", "value");
        ledger.put("  ", "owner", "value");
        ledger.put("id", "owner", null);

        assertTrue(ledger.ids().isEmpty());
    }

    @Test
    void reRegisteringTheSameInstanceIsSilentAndKeepsTheOriginalOwner() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();
        String handler = "shared-instance";

        ledger.put("hub", "modA", handler);
        ledger.recordFailure("hub", "boom");
        ledger.put("hub", "modB", handler); // same instance, a different owner string this time

        RegistrationInfo info = ledger.info().get("hub");
        assertEquals("modA", info.owner(), "an idempotent re-registration must not attribute a new owner");
        assertEquals(1, info.failures(), "an idempotent re-registration must not reset failure history");
    }

    @Test
    void overwritingWithADifferentInstanceReplacesTheValueAndTheOwner() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        ledger.put("hub", "modA", "handler-1");
        ledger.recordFailure("hub", "boom");
        ledger.put("hub", "modB", "handler-2");

        assertEquals("handler-2", ledger.get("hub"));
        RegistrationInfo info = ledger.info().get("hub");
        assertEquals("modB", info.owner(), "a genuine overwrite takes on the new owner");
        assertEquals(0, info.failures(), "a genuinely different instance starts its own failure history");
    }

    @Test
    void overwritingTwiceMoreDoesNotThrowAndTheLatestValueWins() {
        // Exercises the once-per-id overwrite-warn path a second and third time; nothing here
        // asserts the log line itself (SafeLog has no test seam), only that repeated genuine
        // overwrites never throw and the ledger always reflects the latest registration.
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        ledger.put("hub", "modA", "handler-1");
        ledger.put("hub", "modB", "handler-2");
        ledger.put("hub", "modC", "handler-3");

        assertEquals("handler-3", ledger.get("hub"));
        assertEquals("modC", ledger.info().get("hub").owner());
    }

    @Test
    void recordFailureAccumulatesCountAndKeepsTheLatestMessage() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();
        ledger.put("hub", "modA", "handler");

        ledger.recordFailure("hub", "first failure");
        ledger.recordFailure("hub", "second failure");

        RegistrationInfo info = ledger.info().get("hub");
        assertEquals(2, info.failures());
        assertEquals("second failure", info.lastFailure());
    }

    @Test
    void recordFailureOnAnUnregisteredIdIsANoOp() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();

        ledger.recordFailure("nothing", "boom");

        assertTrue(ledger.info().isEmpty());
        assertFalse(ledger.isRegistered("nothing"));
    }

    @Test
    void infoIsAFreshSnapshotCoveringEveryRegisteredId() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();
        ledger.put("a", "modA", "1");
        ledger.put("b", "modB", "2");

        Map<String, RegistrationInfo> snapshot = ledger.info();

        assertEquals(2, snapshot.size());
        assertEquals("modA", snapshot.get("a").owner());
        assertEquals("modB", snapshot.get("b").owner());

        ledger.put("c", "modC", "3");
        assertEquals(2, snapshot.size(), "a previously taken snapshot must not see a later registration");
        assertEquals(3, ledger.info().size(), "a fresh call sees it");
    }

    @Test
    void clearDropsEveryRegistration() {
        PlacementRegistryLedger<String> ledger = new PlacementRegistryLedger<>();
        ledger.put("a", "modA", "1");

        ledger.clear();

        assertTrue(ledger.ids().isEmpty());
        assertTrue(ledger.info().isEmpty());
        assertFalse(ledger.isRegistered("a"));
    }
}
