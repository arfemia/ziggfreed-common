package com.ziggfreed.common.track.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The World Rules form's writer keeps a rule's grant switches whole: an owner entry's {@code Rules.Grants.Kinds} map
 * replaces the rule file's whole, so a switch written alone would turn every other switch the file writes back on.
 * Pure: {@link TrackWorldRuleForm#wholeKinds} over plain maps. And a rule reads back through its own codec, which the
 * form's list and its Defaults read it by.
 */
class TrackWorldRuleFormTest {

    private static final String KINDS = "Rules.Grants.Kinds.";

    /** A rule file writing all three switches off, as a minigame world's rule does. */
    private static Map<String, Boolean> fileMap() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put("Ability", false);
        map.put("Combat", false);
        map.put("Stat", false);
        return map;
    }

    @Test
    void oneSwitchWritesEverySwitchAsItReadsWithTheChange() {
        Map<String, Object> out = TrackWorldRuleForm.wholeKinds(fileMap(), null, Map.of(KINDS + "Stat", true));
        assertEquals(Boolean.FALSE, out.get(KINDS + "Ability"), "the file's other switches are written as they read");
        assertEquals(Boolean.FALSE, out.get(KINDS + "Combat"), "the file's other switches are written as they read");
        assertEquals(Boolean.TRUE, out.get(KINDS + "Stat"), "the changed switch is written");
        assertEquals(3, out.size(), "nothing else is written: " + out);
    }

    @Test
    void aResetBackToTheFilesSwitchesTakesTheOwnersMapOutWhole() {
        Map<String, Boolean> owner = fileMap();
        owner.put("Stat", true);
        Map<String, Object> leaves = new HashMap<>();
        leaves.put(KINDS + "Stat", null);
        Map<String, Object> out = TrackWorldRuleForm.wholeKinds(fileMap(), owner, leaves);
        assertEquals(1, out.size(), "only the map's removal is written: " + out);
        assertTrue(out.containsKey("Rules.Grants.Kinds") && out.get("Rules.Grants.Kinds") == null,
                "the owner's map comes out, so the rule file's applies again: " + out);
    }

    @Test
    void aWriteWithNoSwitchPassesAsGiven() {
        Map<String, Object> leaves = Map.of("Rules.Gain.Multiplier", 0.5);
        assertSame(leaves, TrackWorldRuleForm.wholeKinds(fileMap(), null, leaves));
    }

    /**
     * The form lists a rule and reads its Default through the rule's own codec (the keyed store's binding encodes
     * each rule), so a rule that cannot be encoded would drop off the screen without a word.
     */
    @Test
    void aRuleReadsBackThroughItsCodecWithItsWorldsAndSwitches() throws IOException {
        String json = "{ \"Where\": { \"Match\": [ \"*Arena*\" ] }, \"Enabled\": false, \"Rules\": { \"Gain\": "
                + "{ \"Multiplier\": 0.5, \"DisabledTracks\": [ \"Mining\" ] }, \"Grants\": { \"Kinds\": "
                + "{ \"Stat\": false } } } }";
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackWorldRuleAsset.class, "Your_Arenas", null);
        TrackWorldRuleAsset rule = TrackWorldRuleAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
        BsonValue encoded = TrackWorldRuleAsset.CODEC.encode(rule, new ExtraInfo());
        assertTrue(encoded != null && encoded.isDocument(), "a rule encodes as a document: " + encoded);
        BsonDocument doc = encoded.asDocument();
        assertEquals("*Arena*", doc.getDocument("Where").getArray("Match").get(0).asString().getValue());
        assertEquals(false, doc.getBoolean("Enabled").getValue());
        BsonDocument rules = doc.getDocument("Rules");
        assertEquals(0.5, rules.getDocument("Gain").getDouble("Multiplier").getValue());
        assertEquals(false, rules.getDocument("Grants").getDocument("Kinds").getBoolean("Stat").getValue());
    }
}
