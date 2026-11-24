package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * Saved builds past {@code Loadouts.Max} open on their own row (the shared slot row a stance's slots use), the build's
 * number read off the row's id and {@code {Track}} in its key pick standing for the track's id; a track writing no rows
 * keeps exactly 1 to {@code Max} and asks nobody anything. And {@code ziggfreedcommon:track_picks_taken}'s count: the
 * owned takes of every pick or option a pattern names, {@code *} anywhere in it.
 */
class LoadoutSlotsTest {

    private static final Predicate<String> NOBODY = id -> {
        throw new AssertionError("a track with no rows asks no key");
    };

    private static final Predicate<GateSpec> NO_GATE = spec -> {
        throw new AssertionError("a track with no rows asks no Requires");
    };

    @Test
    void aTrackWithNoRowsKeepsOneToMaxAsBefore() throws IOException {
        TrackAsset track = track("{ \"Loadouts\": { \"Max\": 2 } }");
        List<SlotRow> rows = track.loadouts().slots();
        assertEquals(null, rows, "no Slots written");
        assertEquals(List.of(1, 2), LoadoutSlots.open(2, List.of(), "Your_Track", NOBODY, NO_GATE));
        assertEquals(List.of(), LoadoutSlots.open(0, List.of(), "Your_Track", NOBODY, NO_GATE), "none kept");
        assertTrue(LoadoutSlots.isOpen(2, 2, List.of(), "Your_Track", NOBODY, NO_GATE));
        assertFalse(LoadoutSlots.isOpen(3, 2, List.of(), "Your_Track", NOBODY, NO_GATE));
        assertFalse(LoadoutSlots.isOpen(0, 2, List.of(), "Your_Track", NOBODY, NO_GATE));
    }

    @Test
    void aRowPastMaxOpensOnItsKeyPickOnTheTrack() throws IOException {
        TrackAsset track = track("{ \"Loadouts\": { \"Max\": 2, \"Slots\": ["
                + " { \"Id\": \"II\", \"Pick\": \"Never_Asked\" },"
                + " { \"Id\": \"III\", \"Pick\": \"Your_Build_Key_{Track}\" },"
                + " { \"Id\": \"Fourth\" },"
                + " { \"Id\": \"5\", \"Requires\": { \"Factors\": [ { \"Factor\": \"ziggfreedcommon:track_level\","
                + " \"Param\": \"Your_Track\", \"Min\": 50 } ] } } ] } }");
        List<SlotRow> rows = track.loadouts().slots();
        assertEquals("your_build_key_mining", rows.get(1).pick("Mining"), "{Track} is the track's id");
        assertEquals("your_build_key_{track}", rows.get(1).pick(), "with no track, the key as written");

        Predicate<String> keyed = "your_build_key_mining"::equals;
        assertEquals(List.of(1, 2), LoadoutSlots.open(2, rows, "Mining", id -> false, spec -> false),
                "a row numbered at or under Max changes nothing, an unnumbered row opens nothing");
        assertEquals(List.of(1, 2, 3), LoadoutSlots.open(2, rows, "Mining", keyed, spec -> false),
                "the third build opens on its key");
        assertEquals(List.of(1, 2, 5), LoadoutSlots.open(2, rows, "Woodcutting", keyed, spec -> true),
                "another track's key is another pick; a row's Requires opens it");
        assertTrue(LoadoutSlots.isOpen(3, 2, rows, "Mining", keyed, spec -> false));
        assertFalse(LoadoutSlots.isOpen(3, 2, rows, "Mining", id -> false, spec -> false), "closed without its key");
    }

    @Test
    void aRowsIdReadsAsItsBuildNumber() {
        assertEquals(1, SlotRow.numberOf("I"));
        assertEquals(3, SlotRow.numberOf("iii"), "any case");
        assertEquals(4, SlotRow.numberOf("IV"));
        assertEquals(9, SlotRow.numberOf("IX"));
        assertEquals(12, SlotRow.numberOf("XII"));
        assertEquals(3, SlotRow.numberOf("3"));
        assertEquals(0, SlotRow.numberOf("IIII"), "written the usual way only");
        assertEquals(0, SlotRow.numberOf("Third"));
        assertEquals(0, SlotRow.numberOf(""));
        assertEquals(0, SlotRow.numberOf(null));
    }

    @Test
    void picksTakenSumsTheOwnedTakesOfEveryIdAPatternNames() {
        PickState state = PickState.EMPTY.withTakes("Your_Swords_Eternal", 3).withTakes("Your_Fire_Eternal", 2)
                .withTakes("Your_Swords_Honed_II", 1).withTakes("Your_Eternal_Flair", 1)
                .withChoice(PickState.slotKey("Your_Choice", "g", 2), List.of("Your_Option_Eternal"));
        assertEquals(3 + 2 + 1, TrackPicks.takenMatching(state, "Your_*_Eternal"),
                "every id the pattern names, a choice's option included");
        assertEquals(1, TrackPicks.takenMatching(state, "YOUR_*_HONED_II"), "any case");
        assertEquals(1, TrackPicks.takenMatching(state, "Your_Choice"), "a choice's own id counts its slots");
        assertEquals(3, TrackPicks.takenMatching(state, "Your_Swords_Eternal"), "a pattern with no star is one id");
        assertEquals(0, TrackPicks.takenMatching(state, "Nobody_*"));
        assertEquals(0, TrackPicks.takenMatching(state, " "), "a blank pattern counts nothing");
    }

    private static TrackAsset track(String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackAsset.class, "Your_Track", null);
        return TrackAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }
}
