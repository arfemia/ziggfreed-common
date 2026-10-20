package com.ziggfreed.common.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.asset.DerivedReading;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * A derived track's {@code Derived} group decodes as written (its sources in authored order, its reading a closed
 * word that reads Highest when unwritten or unknown), and the one rule every reader shares picks the first source at
 * the highest level, so a tie lands a write on the source listed first.
 */
class TrackDerivedCodecTest {

    @Test
    void theDerivedGroupDecodesItsSourcesInOrder() throws IOException {
        TrackAsset track = decode("{ \"Curve\": \"Skill_Hybrid\", \"Derived\": { \"From\": { \"Tracks\": [ \"B\", \" \","
                + " \"A\" ], \"Categories\": [ \"Schools\" ] } } }");
        assertTrue(track.isDerived());
        assertEquals(List.of("B", "A"), track.derived().tracks(), "authored order, blanks left out");
        assertEquals(List.of("Schools"), track.derived().categories());
        assertEquals(DerivedReading.HIGHEST, track.derived().reading(), "Highest when unwritten");
    }

    @Test
    void theReadingIsAClosedWord() throws IOException {
        assertEquals(DerivedReading.SUM, decode("{ \"Derived\": { \"Reading\": \"sum\" } }").derived().reading());
        assertEquals(DerivedReading.HIGHEST, decode("{ \"Derived\": { \"Reading\": \"Most\" } }").derived().reading(),
                "an unknown word reads as unwritten");
    }

    @Test
    void anOrdinaryTrackIsNotDerived() throws IOException {
        TrackAsset track = decode("{ \"Curve\": \"Skill_Hybrid\" }");
        assertFalse(track.isDerived());
        assertNull(track.derived());
    }

    @Test
    void aTieGoesToTheFirstSourceListed() {
        Map<String, Integer> levels = Map.of("arcana", 30, "pyromancy", 30, "frostweaving", 12);
        List<String> sources = List.of("arcana", "pyromancy", "frostweaving");
        assertEquals("arcana", TrackDerivation.highest(sources, levels::get));
        assertEquals("pyromancy", TrackDerivation.highest(List.of("frostweaving", "pyromancy", "arcana"), levels::get),
                "the higher one wins whatever its place");
        assertEquals(30, TrackDerivation.combine(DerivedReading.HIGHEST, sources, levels::get));
        assertEquals(72, TrackDerivation.combine(DerivedReading.SUM, sources, levels::get));
    }

    private static TrackAsset decode(String json) throws IOException {
        TrackAsset noParent = null;
        return TrackAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackAsset.class, "Your_Derived", null)));
    }
}
