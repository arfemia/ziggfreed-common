package com.ziggfreed.common.track;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.curve.CurveTable;

/**
 * Where a gain stops: at the start of the track's own {@code Cap}, or of a lower cap a factor set for this one gain
 * ({@code GainCall.capAtLevel}, a permission's level cap), the fraction dropped there, exactly as the MMO's own award
 * stopped a skill at its owner's and its permission's cap; and a climb already at or past the cap gains nothing.
 */
class TrackGainCapTest {

    private static final CurveTable CURVE = CurveTable.build(List.of(CurveTable.Piece.hybrid(300, 200, 200)), 0L,
            List.of());

    @Test
    void aFactorsCapStopsTheGainAtItsLevelsStart() throws IOException {
        TrackDef def = def(200);
        long start10 = CURVE.floorOf(10);
        TrackRecord before = new TrackRecord(start10 - 5, 0.5, 9, 0, Map.of());
        TrackRecord after = TrackService.moved(def, before, 100.0, 10);
        assertEquals(start10, after.value(), "the gain stops at level 10's start");
        assertEquals(0.0, after.fraction(), "the fraction is dropped there");
        assertEquals(10, def.level(after));
    }

    @Test
    void aClimbAtTheCapGainsNothingAndDropsItsFraction() throws IOException {
        TrackDef def = def(200);
        long start10 = CURVE.floorOf(10);
        TrackRecord after = TrackService.moved(def, new TrackRecord(start10, 0.4, 10, 0, Map.of()), 50.0, 10);
        assertEquals(start10, after.value(), "nothing lands past the cap");
        assertEquals(0.0, after.fraction());
    }

    @Test
    void theTracksOwnCapStandsOverAHigherOne() throws IOException {
        TrackDef def = def(5);
        TrackRecord after = TrackService.moved(def, TrackRecord.EMPTY, 1.0e12, 50);
        assertEquals(CURVE.floorOf(5), after.value(), "the track's Cap 5 holds under a factor's 50");
    }

    @Test
    void noCapLeavesTheGainWholeWithItsFractionCarried() throws IOException {
        TrackDef def = def(200);
        TrackRecord after = TrackService.moved(def, new TrackRecord(1_000L, 0.5, 2, 0, Map.of()), 12.75, 0);
        assertEquals(1_013L, after.value(), "12.75 and the carried 0.5 land as 13 whole");
        assertEquals(0.25, after.fraction(), 1e-12, "and 0.25 carries on");
    }

    private static TrackDef def(int cap) throws IOException {
        String json = "{ \"Curve\": \"Skill_Hybrid\", \"Cap\": " + cap + " }";
        TrackAsset noParent = null;
        TrackAsset asset = TrackAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), noParent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackAsset.class, "Your_Skill", null)));
        return new TrackDef(asset, CURVE);
    }
}
