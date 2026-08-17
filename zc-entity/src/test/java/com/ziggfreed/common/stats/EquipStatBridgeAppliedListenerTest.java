package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Coverage for the {@link EquipStatBridge} post-apply seam: registration semantics on a bound
 * bridge instance, and the isolation rule {@link EquipStatBridge#forEachIsolated} enforces (a
 * throwing listener never suppresses the ones after it). Engine-free - the dispatch helper is
 * generic over the listener type precisely so the rule can be driven without a live store.
 */
class EquipStatBridgeAppliedListenerTest {

    @Test
    void addAppliedListener_isIdempotentAndNullSafe() {
        EquipStatBridge bridge = EquipStatBridge.install("test");
        EquipStatBridge.AppliedListener listener = (store, ref) -> { };

        bridge.addAppliedListener(null); // must not throw
        bridge.addAppliedListener(listener);
        bridge.addAppliedListener(listener); // same instance, already registered
        bridge.removeAppliedListener(null); // must not throw

        // Assert against the REAL roster: a stand-in list would report 1 whatever the bridge did,
        // so it could never catch a regression that dropped the identity guard.
        assertEquals(1, bridge.appliedListenersView().size(),
                "re-adding the same listener instance must not register it twice");

        // And the roster is what actually gets dispatched.
        List<Integer> hits = new ArrayList<>();
        EquipStatBridge.forEachIsolated(bridge.appliedListenersView(), l -> hits.add(1), "test");
        assertEquals(1, hits.size());

        bridge.removeAppliedListener(listener);
        assertTrue(bridge.appliedListenersView().isEmpty(), "remove must drop the registered listener");
    }

    @Test
    void addAppliedListener_dedupsByIdentityOnlyNotByEquivalentBehavior() {
        // Pins the actual scope of the idempotency guarantee: two SEPARATELY-created listener
        // instances that do the identical thing are two different objects, so both register - only
        // re-passing the SAME stored reference (above) is deduped.
        EquipStatBridge bridge = EquipStatBridge.install("test");
        List<Integer> hits = new ArrayList<>();
        EquipStatBridge.AppliedListener first = (store, ref) -> hits.add(1);
        EquipStatBridge.AppliedListener second = (store, ref) -> hits.add(1);

        bridge.addAppliedListener(first);
        bridge.addAppliedListener(second);

        assertEquals(2, bridge.appliedListenersView().size(),
                "two distinct listener instances are not deduped by the identity check");

        EquipStatBridge.forEachIsolated(bridge.appliedListenersView(), l -> hits.add(1), "test");
        assertEquals(2, hits.size());
    }

    @Test
    void forEachIsolated_runsEveryListenerEvenWhenOneThrows() {
        List<String> ran = new ArrayList<>();
        List<Runnable> listeners = List.of(
                () -> ran.add("first"),
                () -> {
                    throw new IllegalStateException("listener blew up");
                },
                () -> ran.add("third"));

        EquipStatBridge.forEachIsolated(listeners, Runnable::run, "test");

        assertEquals(List.of("first", "third"), ran,
                "a throwing listener must not suppress the listeners registered after it");
    }

    @Test
    void forEachIsolated_emptyRosterIsANoOp() {
        List<String> ran = new ArrayList<>();
        EquipStatBridge.forEachIsolated(List.<Runnable>of(), r -> ran.add("ran"), "test");
        assertTrue(ran.isEmpty());
    }
}
