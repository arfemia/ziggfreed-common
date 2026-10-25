package com.ziggfreed.common.track.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The world rules as a file writes them: {@code Gain.PlayerHits} decodes and pays only when written true (R864),
 * and a {@code Grants.Kinds} word takes a grant away by its kind or by any family it belongs to, off winning.
 */
class TrackRulesCodecTest {

    @Test
    void playerHitsPayOnlyWhenWrittenTrue() throws IOException {
        TrackRules on = decode("{ \"Gain\": { \"PlayerHits\": true } }");
        assertEquals(Boolean.TRUE, on.gain().playerHits());
        assertTrue(on.paysPlayerHits());
        assertFalse(decode("{ \"Gain\": { \"PlayerHits\": false } }").paysPlayerHits());
        TrackRules unwritten = decode("{ \"Gain\": { \"Multiplier\": 2.0 } }");
        assertNull(unwritten.gain().playerHits(), "unauthored stays unauthored");
        assertFalse(unwritten.paysPlayerHits(), "and reads as off");
        assertFalse(decode("{ }").paysPlayerHits(), "no Gain group pays no hit on a player");
    }

    @Test
    void aFamilyWordTakesItsGrantsAway() throws IOException {
        TrackRules rules = decode("{ \"Grants\": { \"Kinds\": { \"Combat\": false } } }");
        assertFalse(rules.grantsOn("Stat", List.of("Combat"), "Swords", null), "a stat tagged Combat goes");
        assertTrue(rules.grantsOn("Stat", List.of(), "Swords", null), "an untagged stat stays");
        assertTrue(rules.grantsOn("Stat", null, "Swords", null));
        assertFalse(rules.grantsOn("Stat", List.of("Mobility", " combat "), "Swords", null),
                "any one family, without regard to case");
    }

    /** R895: a family word, when written, decides over its kind's word; the kind decides only where none is. */
    @Test
    void aWrittenFamilyWordDecidesOverItsKind() throws IOException {
        TrackRules statOff = decode("{ \"Grants\": { \"Kinds\": { \"Stat\": false, \"Combat\": true } } }");
        assertTrue(statOff.grantsOn("Stat", List.of("Combat"), "Swords", null), "Combat true keeps a Combat stat");
        assertFalse(statOff.grantsOn("Stat", List.of(), "Swords", null), "Stat false takes an untagged stat");
        TrackRules onlyStatOff = decode("{ \"Grants\": { \"Kinds\": { \"Stat\": false } } }");
        assertFalse(onlyStatOff.grantsOn("Stat", List.of("Combat"), "Swords", null),
                "with no family word written the kind decides");
        TrackRules mixed = decode("{ \"Grants\": { \"Kinds\": { \"Stat\": true, \"Combat\": true, "
                + "\"Ability\": false } } }");
        assertFalse(mixed.grantsOn("Stat", List.of("Combat", "Ability"), "Swords", null),
                "among written families, false wins");
    }

    @Test
    void aTrackEntryNamesAFamilyForThatTrackAlone() throws IOException {
        TrackRules rules = decode("{ \"Grants\": { \"Tracks\": { \"Swords\": { \"Kinds\": { \"Combat\": false } } } } }");
        assertFalse(rules.grantsOn("Stat", List.of("Combat"), "swords", null));
        assertTrue(rules.grantsOn("Stat", List.of("Combat"), "Mining", null));
        assertFalse(rules.grantsOn("Stat", List.of("Combat"), "Your_Blade", "Swords"), "or its category");
    }

    private static TrackRules decode(String json) throws IOException {
        return TrackRules.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
