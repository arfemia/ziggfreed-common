package com.ziggfreed.common.npc.placement.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.season.SeasonGate;

/**
 * The {@code Seasons} leaf's load rule: the first listed entry whose event runs is in effect, its authored
 * leaves replace the file's whole while the rest read the file's, the character's id never moves, an entry
 * with its own anchor stands under its own tag, and the list (not the file's {@code Season}) is the gate.
 */
class PlacementSeasonsTest {

    private static final String TOMAS = "{"
            + " \"Season\": \"Harvest_Feast\","
            + " \"Identity\": { \"Role\": \"Harvest_Feast_Miller\", \"NpcId\": \"Harvest_Feast_Miller\" },"
            + " \"Anchor\": { \"Structure\": { \"MarkerIds\": [ \"Temple_Kweebec_Merchant_Static\" ] } },"
            + " \"Interact\": { \"Dialogue\": \"Harvest_Feast_Miller\" },"
            + " \"Seasons\": ["
            + "   { \"Season\": \"Harvest_Feast\" },"
            + "   { \"Season\": \"Christmas\", \"Role\": \"Christmas_Miller\","
            + "     \"Interact\": { \"Dialogue\": \"Christmas_Miller\" },"
            + "     \"Anchor\": { \"WorldSpawn\": { \"Offset\": { \"X\": -18, \"Z\": -8 } } } } ] }";

    private AtomicBoolean feastLive;
    private AtomicBoolean christmasLive;

    @BeforeEach
    void declareTheCalendarsSwitches() {
        feastLive = new AtomicBoolean(false);
        christmasLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", feastLive::get);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Christmas_Live", "test", christmasLive::get);
    }

    @AfterEach
    void forget() {
        FeatureFlags.reset();
    }

    private static NpcPlacementAsset decode(String json) throws IOException {
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(NpcPlacementAsset.class, "Harvest_Feast_Miller_Temple",
                        null)));
    }

    @Test
    void theFirstRunningEntryIsInEffectAndKeepsTheFilesOwnLeavesItLeavesOut() throws IOException {
        NpcPlacementAsset tomas = decode(TOMAS);
        assertEquals(2, tomas.getSeasons().size());
        feastLive.set(true);
        christmasLive.set(true);

        NpcPlacementAsset view = tomas.inEffect();
        assertEquals("Harvest_Feast", tomas.activeSeason().getSeason(), "both run: the first listed wins");
        assertSame(tomas, view, "an entry authoring nothing stands as the file itself");
        assertNull(view.anchorTag());
        assertTrue(tomas.ownsAnchorKey("structure:temple_marker"));
    }

    @Test
    void onlyChristmasRunningWearsItsRoleSpeaksItsLinesAndStandsAtItsOwnSpot() throws IOException {
        NpcPlacementAsset tomas = decode(TOMAS);
        christmasLive.set(true);

        NpcPlacementAsset view = tomas.inEffect();
        assertEquals("Christmas_Miller", view.getIdentity().getRole());
        assertEquals("Harvest_Feast_Miller", view.getIdentity().getNpcId(), "the character never changes");
        assertEquals("Christmas_Miller", view.getInteract().getDialogue());
        assertNull(view.getAnchor().getStructure(), "an entry's Anchor replaces the file's whole");
        assertEquals("christmas", view.anchorTag());
        assertFalse(tomas.ownsAnchorKey("structure:temple_marker"), "the feast's copy goes at the switch");
        assertTrue(tomas.ownsAnchorKey("worldspawn:@christmas~0"));
        assertEquals("Harvest_Feast_Miller", tomas.getIdentity().getRole(), "the file itself is untouched");
    }

    @Test
    void theListIsTheGateAndTheFilesOwnSeasonIsNotRead() throws IOException {
        NpcPlacementAsset tomas = decode(TOMAS);

        assertEquals(PlacementGates.REASON_SEASON, PlacementGates.decide(tomas, null, null).reasonKey());
        christmasLive.set(true);
        assertFalse(PlacementGates.decide(tomas, null, null).isDenied(),
                "Christmas alone stands him although the file's own Season names the feast");
    }

    @Test
    void anAnchorKeyCarriesItsEntrysTagOrNone() {
        assertEquals("christmas", NpcPlacementAsset.anchorTagOf("worldspawn:@christmas~0"));
        assertNull(NpcPlacementAsset.anchorTagOf("structure:12,64,-3"));
        assertNull(NpcPlacementAsset.anchorTagOf("zone:snow~peak"), "an ordinary id holding the separator is untagged");
        assertNull(NpcPlacementAsset.anchorTagOf(null));
    }

    @Test
    void aCopyUnderATagNoEntryCarriesAnyMoreIsNeverOwned() throws IOException {
        NpcPlacementAsset tomas = decode(TOMAS);
        feastLive.set(true);

        assertTrue(tomas.ownsAnchorKey("structure:temple_marker"), "the file's own anchor is in effect");
        assertFalse(tomas.ownsAnchorKey("worldspawn:@winter_fair~0"),
                "a copy left under an entry a pack update renamed or dropped goes, or two copies would stand");
        christmasLive.set(true);
        feastLive.set(false);
        assertFalse(tomas.ownsAnchorKey("worldspawn:@winter_fair~0"));
        assertTrue(tomas.ownsAnchorKey("worldspawn:@christmas~0"));
    }
}
