package com.ziggfreed.common.npc.placement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.ui.route.Destination;

/**
 * {@link NpcPlacementAsset}'s decode contract under native {@code Parent} inheritance.
 *
 * <p>The load-bearing case is {@code Interact}: the terse {@code Dialogue} spelling and the general
 * {@code Open} destination are two ways of writing ONE value, so what is proved here is that they
 * fold to the same model, that authoring both is visible, and that a child inheriting from a
 * {@code Parent} takes a destination as a whole leaf rather than merging one type's fields into
 * another's.
 */
class NpcPlacementAssetCodecTest {

    @BeforeAll
    static void seedDestinations() {
        // The vocabulary is normally seeded in the plugin's setup, before assets are read.
        NpcDestinations.register();
    }

    private static NpcPlacementAsset decode(String json, String id, String parentId, NpcPlacementAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(NpcPlacementAsset.class, id, parentId);
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    private static NpcPlacementAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    // ==================== Interact: one value, two spellings ====================

    @Test
    void theTerseDialogueSpellingIsTheDialogueDestination() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Interact": { "Dialogue": "hub_intro" } }
                """, "hub");

        assertEquals("hub_intro", asset.getInteract().getDialogue());
        assertNull(asset.getInteract().getOpen(), "the terse form authors no Open leaf of its own");

        Destination destination = asset.getInteract().destination();
        assertNotNull(destination, "the terse form must resolve to a destination");
        assertEquals("hub_intro", ((NpcDestinations.Dialogue) destination).getDialogue(),
                "so that press-F and an explicit Open run the very same model");
    }

    @Test
    void anOpenDestinationDecodesItsOwnFields() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Interact": { "Open": { "Type": "Quests", "Npc": "guide" } } }
                """, "quartermaster");

        NpcDestinations.Quests quests = (NpcDestinations.Quests) asset.getInteract().getOpen();
        assertNotNull(quests);
        assertEquals("guide", quests.getNpc());
        assertNull(asset.getInteract().getDialogue());
    }

    @Test
    void aParameterlessDestinationMayBeAuthoredAsOneWord() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Interact": { "Open": "Quests" } }
                """, "greeter");

        NpcDestinations.Quests quests = (NpcDestinations.Quests) asset.getInteract().getOpen();
        assertNotNull(quests, "the bare string must decode as the same type the object form does");
        assertNull(quests.getNpc(), "and carry no fields, so it means the character standing here");
    }

    @Test
    void authoringBothSpellingsIsVisibleAndRunsTheExplicitOne() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Interact": { "Dialogue": "hub_intro", "Open": "Quests" } }
                """, "confused");

        assertTrue(asset.getInteract().hasBothForms(),
                "one press-F described twice must be answerable off the file alone");
        assertTrue(asset.getInteract().destination() instanceof NpcDestinations.Quests,
                "the destination written out in full is what runs, so the behaviour is at least decided");
    }

    @Test
    void aPlacementWithNoInteractOpensNothingOfItsOwn() throws Exception {
        NpcPlacementAsset asset = decodeRoot("{ \"Identity\": { \"Role\": \"Zc_Guide\" } }", "silent");

        assertNull(asset.getInteract(), "no Interact block authored means no destination on the asset");
    }

    // ==================== Interact under Parent ====================

    @Test
    void aChildInheritsTheParentsInteractWhole() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Identity": { "Role": "Zc_Guide" },
                  "Interact": { "Dialogue": "hub_intro" } }
                """, "hub");

        NpcPlacementAsset child = decode("""
                { "Identity": { "NpcId": "hub_temple" } }
                """, "hub_temple", "hub", parent);

        assertEquals("hub_intro", child.getInteract().getDialogue(),
                "a child that swaps the identity keeps the conversation without restating it - the "
                        + "second-world case is a file with no Interact block at all");
    }

    @Test
    void aChildAuthoringADestinationReplacesTheInheritedOneWhole() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Interact": { "Open": { "Type": "Quests", "Npc": "guide" } } }
                """, "base");

        NpcPlacementAsset child = decode("""
                { "Interact": { "Open": { "Type": "Dialogue", "Dialogue": "sage_intro" } } }
                """, "child", "base", parent);

        Destination open = child.getInteract().getOpen();
        assertTrue(open instanceof NpcDestinations.Dialogue,
                "a destination is ONE leaf: authoring it replaces the inherited one rather than merging "
                        + "one type's fields into another type's");
        assertEquals("sage_intro", ((NpcDestinations.Dialogue) open).getDialogue());
    }

    @Test
    void aChildOverridingOneInteractLeafKeepsItsSibling() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Interact": { "Dialogue": "hub_intro" } }
                """, "base");

        NpcPlacementAsset child = decode("""
                { "Interact": { "Dialogue": "temple_intro" } }
                """, "child", "base", parent);

        assertEquals("temple_intro", child.getInteract().getDialogue());
        assertNull(child.getInteract().getOpen(),
                "an untouched leaf in an overridden group stays as the parent left it (unauthored here)");
    }

    // ==================== appendInherited across the groups ====================

    @Test
    void aChildOverridingOneGroupInheritsTheOthers() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Identity": { "Role": "Zc_Guide" },
                  "Where":    { "Match": ["default"] },
                  "Anchor":   { "WorldSpawn": { "Offset": { "X": 2.5 }, "Yaw": 180 } },
                  "Lifecycle":{ "KeepAlive": true, "Fortify": true } }
                """, "hub");

        NpcPlacementAsset child = decode("""
                { "Where": { "GameplayConfig": ["ForgottenTemple"] } }
                """, "hub_temple", "hub", parent);

        assertEquals(List.of("ForgottenTemple"), List.of(child.getWhere().getGameplayConfig()));
        assertEquals("Zc_Guide", child.getIdentity().getRole());
        assertEquals(2.5, child.getAnchor().getWorldSpawn().getOffset().effectiveX());
        assertTrue(child.getLifecycle().effectiveKeepAlive());
        assertTrue(child.getLifecycle().effectiveFortify());

        assertNull(child.getWhere().getMatch(),
                "authoring Where in a child RETARGETS it: the parent's Match must not survive "
                        + "underneath the child's own axis (a leaked Match:[\"default\"] once stood "
                        + "a duplicate NPC at the main world's spawn)");
        assertNull(child.getWhere().match("default", null),
                "so the child must no longer match the world its parent targets");
    }

    @Test
    void aChildAuthoringNoWhereInheritsTheParentsSelectorWhole() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Where": { "Match": ["default"], "ExcludeMatch": ["*Arena*"] } }
                """, "base");

        NpcPlacementAsset child = decode("{ }", "silent", "base", parent);

        assertEquals(List.of("default"), List.of(child.getWhere().getMatch()),
                "omitting Where entirely still inherits the parent's whole selector");
        assertEquals(List.of("*Arena*"), List.of(child.getWhere().getExcludeMatch()),
                "including its ExcludeMatch, since the selector moves as one predicate");
    }

    @Test
    void aChildSwitchingAnchorGroupsMustExplicitlyNullTheOldOneOrBothUnion() throws Exception {
        NpcPlacementAsset parent = decodeRoot("""
                { "Anchor": { "WorldSpawn": { "Offset": { "X": 2.5 }, "Yaw": 180 } } }
                """, "base");

        // Merely authoring a DIFFERENT anchor leaf does not retarget the group: WorldSpawn is its
        // own appendInherited leaf, so an omitted key still inherits the parent's value, and the
        // child ends up with BOTH anchors active - two positions from one placement, which is
        // exactly the double-place shape this package exists to prevent. This is the trap; the
        // next case is the fix.
        NpcPlacementAsset leaking = decode("""
                { "Anchor": { "Structure": { "MarkerIds": ["Some_Marker"] } } }
                """, "leaking", "base", parent);

        assertNotNull(leaking.getAnchor().getWorldSpawn(),
                "omitting WorldSpawn in the child inherits the parent's, however surprising - "
                        + "pinned so nobody assumes an authored sibling leaf silently clears it");
        assertNotNull(leaking.getAnchor().getStructure());

        // Authoring the leaf as an explicit JSON null is how a child actually retargets: the key
        // is PRESENT (so the decode-and-set path runs, not the inherit path) and decodes to Java
        // null, clearing the parent's value rather than falling through to it.
        NpcPlacementAsset retargeted = decode("""
                { "Anchor": { "WorldSpawn": null, "Structure": { "MarkerIds": ["Some_Marker"] } } }
                """, "retargeted", "base", parent);

        assertNull(retargeted.getAnchor().getWorldSpawn(),
                "an explicit JSON null on an appendInherited leaf clears it instead of inheriting - "
                        + "the correct way to switch a Parent child from one anchor group to another");
        assertNotNull(retargeted.getAnchor().getStructure());
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
                { "Requires": { "Factors": [ { "Factor": "yourmod:feature", "Param": "shop", "Min": 1 } ] } }
                """, "gated");

        FactorCondition[] conditions = asset.getRequires().getConditions();
        assertEquals(1, conditions.length);
        assertEquals("yourmod:feature", conditions[0].getFactor());
        assertEquals("shop", conditions[0].getParam());
        assertTrue(conditions[0].accepts(1.0));
        assertTrue(!conditions[0].accepts(0.0));
    }
}
