package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorCondition;

/**
 * {@link NpcPlacementAsset}'s decode contract under native {@code Parent} inheritance.
 *
 * <p>The load-bearing case is {@code Interact.Bindings}: it is a MAP rather than an array so that a
 * child placement can override ONE channel and keep the rest. An array would be a single leaf as
 * far as inheritance is concerned, so authoring it at all would drop every inherited entry. That is
 * the whole reason for the map, so it is proved here rather than assumed.
 */
class NpcPlacementAssetCodecTest {

    private static NpcPlacementAsset decode(String json, String id, String parentId, NpcPlacementAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(NpcPlacementAsset.class, id, parentId);
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    private static NpcPlacementAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    // ==================== Bindings: the map-vs-array decision, proved ====================

    @Test
    void aChildOverridingOneBindingKeepsThePartnersSiblingChannels() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Interact": { "Dialogue": "hub_intro",
                                "Bindings": { "yourmod:ui_target": { "Value": "hub" },
                                              "yourmod:npc_id":    { "Value": "guide" } } } }
                """, "hub");

        NpcPlacementAsset child = decode("""
                { "Interact": { "Bindings": { "yourmod:npc_id": { "Value": "guide_temple" } } } }
                """, "hub_temple", "hub", parent);

        Map<String, PlacementBinding> bindings = child.getInteract().getBindings();
        assertEquals("guide_temple", bindings.get("yourmod:npc_id").getValue(),
                "the child's own channel must win");
        assertNotNull(bindings.get("yourmod:ui_target"),
                "a channel the child did not mention must survive - this is what the map buys over an array");
        assertEquals("hub", bindings.get("yourmod:ui_target").getValue());
        assertEquals("hub_intro", child.getInteract().getDialogue(),
                "a sibling leaf in the same group must survive the partial override");
    }

    @Test
    void aChildBindingMergesLeafWiseWithinOneChannel() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Interact": { "Bindings": { "yourmod:slot": { "Value": "a", "Amount": 3.0 } } } }
                """, "base");

        NpcPlacementAsset child = decode("""
                { "Interact": { "Bindings": { "yourmod:slot": { "Value": "b" } } } }
                """, "child", "base", parent);

        PlacementBinding slot = child.getInteract().getBindings().get("yourmod:slot");
        assertEquals("b", slot.getValue());
        assertEquals(3.0, slot.getAmount(),
                "one channel's untouched leaf must survive too, not just one channel among many");
    }

    // ==================== appendInherited across the groups ====================

    @Test
    void aChildOverridingOneGroupInheritsTheOthers() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Identity": { "Role": "Zc_Guide" },
                  "Where":    { "Names": ["primary"] },
                  "Anchor":   { "WorldSpawn": { "Offset": { "X": 2.5 }, "Yaw": 180 } },
                  "Lifecycle":{ "KeepAlive": true, "Fortify": true } }
                """, "hub");

        NpcPlacementAsset child = decode("""
                { "Where": { "Names": ["forgotten_temple"] } }
                """, "hub_temple", "hub", parent);

        assertEquals(List.of("forgotten_temple"), List.of(child.getWhere().getNames()));
        assertEquals("Zc_Guide", child.getIdentity().getRole());
        assertEquals(2.5, child.getAnchor().getWorldSpawn().getOffset().effectiveX());
        assertTrue(child.getLifecycle().effectiveKeepAlive());
        assertTrue(child.getLifecycle().effectiveFortify());
    }

    @Test
    void aChildOverridingOneLeafInsideAGroupKeepsItsSiblings() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Limits": { "SpawnChance": 0.5, "MaxPerWorld": 3, "OncePerWorld": true } }
                """, "base");

        NpcPlacementAsset child = decode("""
                { "Limits": { "MaxPerWorld": 7 } }
                """, "child", "base", parent);

        assertEquals(7, child.getLimits().effectiveMaxPerWorld());
        assertEquals(0.5, child.getLimits().effectiveSpawnChance(),
                "an untouched leaf in an overridden group must survive (appendInherited per leaf)");
        assertTrue(child.getLimits().effectiveOncePerWorld());
    }

    // ==================== reader defaults ====================

    @Test
    void absentKeysDecodeToNullAndReadAsTheDocumentedDefaults() throws Exception {
        NpcPlacementAsset asset = decodeRoot("{ \"Identity\": { \"Role\": \"Zc_Guide\" } }", "bare");

        assertTrue(asset.isEnabled(), "an unauthored Enabled means the placement ships on");
        assertNull(asset.getWhere());
        assertNull(asset.getAnchor());
        assertNull(asset.getLimits());
        assertNull(asset.getLifecycle());
    }

    @Test
    void everyLifecycleKnobIsOptInAndDefaultsOff() throws Exception {
        NpcPlacementAsset asset = decodeRoot("{ \"Lifecycle\": { } }", "opt_in");

        assertTrue(!asset.getLifecycle().effectiveKeepAlive());
        assertTrue(!asset.getLifecycle().effectiveRespawn());
        assertTrue(!asset.getLifecycle().effectiveFortify());
    }

    @Test
    void aCustomAnchorCarriesItsOpaqueParams() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Anchor": { "Custom": { "Provider": "yourmod:station_block",
                                          "Params": { "Station": "sawmill" } } } }
                """, "custom");

        assertEquals("yourmod:station_block", asset.getAnchor().getCustom().getProvider());
        assertEquals("sawmill", asset.getAnchor().getCustom().getParams().get("Station"));
    }

    @Test
    void requiresDecodesItsConditionArray() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Requires": { "Conditions": [ { "Factor": "yourmod:feature", "Param": "shop", "Min": 1 } ] } }
                """, "gated");

        FactorCondition[] conditions = asset.getRequires().getConditions();
        assertEquals(1, conditions.length);
        assertEquals("yourmod:feature", conditions[0].getFactor());
        assertEquals("shop", conditions[0].getParam());
        assertTrue(conditions[0].accepts(1.0));
        assertTrue(!conditions[0].accepts(0.0));
    }
}
