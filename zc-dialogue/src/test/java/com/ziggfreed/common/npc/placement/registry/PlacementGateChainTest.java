package com.ziggfreed.common.npc.placement.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementOverrides;
import com.ziggfreed.common.npc.placement.registry.PlacementGate.GateContext;
import com.ziggfreed.common.npc.placement.registry.PlacementGate.GateVerdict;

/**
 * The gate chain: any deny wins, the FIRST deny is the one reported, a throwing gate cannot wipe
 * the server's NPCs, and the owner-override resolution behind the built-in admin gate.
 */
class PlacementGateChainTest {

    private static GateContext ctx(String id) {
        return new GateContext(NpcPlacementAsset.of(id, null, null, null, null, null, null, null, null),
                null, null);
    }

    private static PlacementGate allow() {
        return c -> GateVerdict.allow();
    }

    private static PlacementGate deny(String reason) {
        return c -> GateVerdict.deny(reason);
    }

    // ==================== any deny wins, in order ====================

    @Test
    void anEmptyChainAllows() {
        assertFalse(PlacementGates.decideWith(List.of(), ctx("hub")).isDenied());
    }

    @Test
    void oneDenyAnywhereInTheChainWins() {
        assertTrue(PlacementGates.decideWith(List.of(allow(), allow(), deny("nope")), ctx("hub")).isDenied());
        assertTrue(PlacementGates.decideWith(List.of(deny("nope"), allow()), ctx("hub")).isDenied());
    }

    @Test
    void theFirstDenyIsTheOneReported() {
        GateVerdict verdict = PlacementGates.decideWith(
                List.of(allow(), deny("first"), deny("second")), ctx("hub"));

        assertEquals("first", verdict.reasonKey(),
                "ordering matters: a listing should name the most fundamental reason, not the last "
                        + "one checked");
    }

    @Test
    void aThrowingGateIsSkippedRatherThanTreatedAsADeny() {
        PlacementGate broken = c -> {
            throw new IllegalStateException("a third-party veto blew up");
        };

        assertFalse(PlacementGates.decideWith(List.of(broken, allow()), ctx("hub")).isDenied(),
                "a broken consumer veto must never be able to despawn every NPC on the server");
    }

    @Test
    void anAllowVerdictCarriesNoReason() {
        assertNull(GateVerdict.allow().reasonKey());
        assertTrue(GateVerdict.allow().allowed());
    }

    // ==================== the built-in sources ====================

    @Test
    void theAssetsOwnEnabledLeafIsADeny() {
        NpcPlacementAsset off = NpcPlacementAsset.of("hub", Boolean.FALSE, null, null, null, null, null, null, null);
        assertFalse(off.isEnabled());

        PlacementGate enabledGate = c -> c.placement().isEnabled()
                ? GateVerdict.allow()
                : GateVerdict.deny(PlacementGates.REASON_DISABLED);

        GateVerdict verdict = PlacementGates.decideWith(List.of(enabledGate),
                new GateContext(off, null, null));
        assertEquals(PlacementGates.REASON_DISABLED, verdict.reasonKey());
    }

    @Test
    void theLiveChainShipsWithTheThreeBuiltIns() {
        assertTrue(PlacementGates.size() >= 3,
                "the asset switch, the owner override, and the authored Requires block");
    }

    // ==================== owner-override resolution ====================

    @Test
    void anUnmentionedPlacementIsEnabled() {
        assertNull(NpcPlacementOverrides.resolve(Map.of(), "hub"));
    }

    @Test
    void anExactIdBeatsAPrefixWhichBeatsTheGlobalStop() {
        Map<String, Boolean> entries = Map.of(
                "*", false,
                "mmo_*", true,
                "mmo_hub", false);

        assertEquals(Boolean.FALSE, NpcPlacementOverrides.resolve(entries, "mmo_hub"));
        assertEquals(Boolean.TRUE, NpcPlacementOverrides.resolve(entries, "mmo_shop"));
        assertEquals(Boolean.FALSE, NpcPlacementOverrides.resolve(entries, "other_thing"));
    }

    @Test
    void theLongestMatchingPrefixWins() {
        Map<String, Boolean> entries = Map.of(
                "mmo_*", false,
                "mmo_hub_*", true);

        assertEquals(Boolean.TRUE, NpcPlacementOverrides.resolve(entries, "mmo_hub_temple"));
        assertEquals(Boolean.FALSE, NpcPlacementOverrides.resolve(entries, "mmo_shop"));
    }

    @Test
    void aGlobalStopWithOneReEnableLeavesExactlyThatOneStanding() {
        Map<String, Boolean> entries = Map.of("*", false, "mmo_hub", true);

        assertEquals(Boolean.TRUE, NpcPlacementOverrides.resolve(entries, "mmo_hub"));
        assertEquals(Boolean.FALSE, NpcPlacementOverrides.resolve(entries, "mmo_shop"));
    }

    @Test
    void resolutionIsCaseInsensitive() {
        assertEquals(Boolean.FALSE, NpcPlacementOverrides.resolve(Map.of("mmo_hub", false), "MMO_Hub"));
    }
}
