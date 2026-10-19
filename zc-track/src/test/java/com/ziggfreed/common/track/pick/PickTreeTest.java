package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.asset.PrestigeBuild;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * A tree's two pure rules (R736, R650): a row opens on the points spent in its branch, each pick's cost once per take,
 * so the gate moves with every take and every undo; and an undo takes the newest pick first, so a row that opened on
 * a take is always undone before the take that opened it. A Remember prestige puts the takes aside in the order they
 * were made (R641). A saved build (R736) replays its choices and takes interleaved as they were made, a swap away
 * after a partial load never loses a saved pick, and a node face taken since takes the saved face's place.
 */
class PickTreeTest {

    private static final Map<String, String> PICKS = Map.of(
            "Your_Toughness", "{ \"Track\": \"Your_Sample\", \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 },"
                    + " \"Repeat\": { \"Max\": 3 } }",
            "Your_Footing", "{ \"Track\": \"Your_Sample\", \"Branch\": \"Guard\", \"Cost\": { \"Points\": 1 },"
                    + " \"Repeat\": { \"Max\": 3 } }",
            "Your_Stand", "{ \"Track\": \"Your_Sample\", \"Branch\": \"Guard\", \"Node\": \"Your_Stance\","
                    + " \"Cost\": { \"Points\": 2 } }",
            "Your_Rush", "{ \"Track\": \"Your_Sample\", \"Branch\": \"Guard\", \"Node\": \"Your_Stance\","
                    + " \"Cost\": { \"Points\": 2 } }",
            "Your_Stride", "{ \"Track\": \"Your_Sample\", \"Branch\": \"Swift\", \"Cost\": { \"Points\": 1 },"
                    + " \"Repeat\": { \"Max\": 3 } }",
            "Your_Root", "{ \"Track\": \"Your_Sample\", \"Cost\": { \"Points\": 1 } }");

    /** A track whose picks sit in no choice node. */
    private static final Function<String, List<String>> NO_NODES = id -> List.of();

    @Test
    void aBranchCountsTheCostOfEveryTakeInIt() throws IOException {
        TrackPicks.Folded folded = fold();
        List<TrackPicks.PickDef> onTrack = folded.byTrack().get("your_sample");
        PickState state = PickState.EMPTY.withTakes("Your_Toughness", 3).withTakes("Your_Footing", 1)
                .withTakes("Your_Stride", 2).withTakes("Your_Root", 1);
        assertEquals(4, TrackPicks.branchSpent(onTrack, state, "Guard"), "three ranks and one, a point each");
        assertEquals(2, TrackPicks.branchSpent(onTrack, state, "SWIFT"), "a branch reads in any case");
        assertEquals(0, TrackPicks.branchSpent(onTrack, state, "Nowhere"), "a branch no pick sits in reads 0");

        PickState opened = state.withTakes("Your_Footing", 1).withTakes("Your_Stand", 1);
        assertEquals(7, TrackPicks.branchSpent(onTrack, opened, "Guard"), "the row's own cost counts too");
        assertEquals(4, TrackPicks.branchSpent(onTrack, opened.withTakes("Your_Stand", -1).withTakes("Your_Footing",
                -1), "Guard"), "undoing the row and the take that opened it closes the gate again");
        TrackRecord record = new TrackRecord(0L, 0.0, 0, 0, Map.of()).withPicks(opened);
        assertEquals(10, TrackPicks.spentOn("Your_Sample", onTrack, id -> record),
                "the pool counts every branch and the root alike");

        TrackPicks.PickDef stand = folded.byId().get("your_stand");
        assertEquals(List.of("your_rush"), stand.otherFaces(), "the faces sharing a Node on a track know each other");
        assertEquals(List.of(), folded.byId().get("your_root").otherFaces(), "a pick in no node has no other face");
    }

    @Test
    void anUndoTakesTheNewestPickFirst() {
        UUID player = UUID.randomUUID();
        try {
            long start = 1_000_000L;
            PickUndo.record(player, take("Your_Toughness", start));
            PickUndo.record(player, take("Your_Footing", start + 1_000L));
            PickUndo.record(player, take("Your_Stand", start + 2_000L));
            assertEquals("your_stand", PickUndo.pop(player, start + 3_000L).pickId(), "the row taken last goes first");
            assertEquals("your_footing", PickUndo.pop(player, start + 3_000L).pickId(), "then the take that opened it");
            assertEquals("your_toughness", PickUndo.pop(player, start + 3_000L).pickId());
            assertNull(PickUndo.pop(player, start + 3_000L), "then nothing");

            PickUndo.record(player, take("Your_Toughness", start));
            PickUndo.record(player, take("Your_Footing", start + PickUndo.WINDOW_MS));
            long late = start + PickUndo.WINDOW_MS + 1L;
            assertEquals("your_footing", PickUndo.pop(player, late).pickId(), "a pick still in its window undoes");
            assertNull(PickUndo.pop(player, late), "one past its window never does, so an older take stays put");
        } finally {
            PickUndo.close(player);
        }
    }

    @Test
    void aRememberPrestigeSetsTheTakesAsideInOrder() {
        PickState state = PickState.EMPTY.withTakes("Your_Footing", 1).withTakes("Your_Toughness", 2)
                .withTakes("Your_Trainer_Gift", 1).withTakes("Your_Footing", 1)
                .withRememberedTakes(List.of("Your_Stride"));
        PickState after = state.afterPrestige(PrestigeBuild.REMEMBER, id -> TrackKey.normalize(id)
                .equals("your_trainer_gift"));
        assertEquals(List.of("your_footing", "your_toughness", "your_toughness", "your_footing", "your_stride"),
                after.rememberedTakes(), "in the order they were made, ahead of what an earlier prestige set aside");
        assertEquals(1, after.takesOf("Your_Trainer_Gift"), "a pick the prestige keeps stays");
        assertEquals(0, after.takesOf("Your_Footing"), "the rest are set aside");
        assertEquals(List.of(), state.afterPrestige(PrestigeBuild.CLEAR, id -> false).rememberedTakes(),
                "Clear sets nothing aside");
    }

    /**
     * A swap away writes the build in use back over the build it came from. After a partial load (a lower climb after
     * a prestige, points not yet back, a pick switched off) the picks in use are fewer than the build: the build
     * stays whole, every saved pick in its place, and what was picked since the load joins its end.
     */
    @Test
    void aSwapAwayAfterAPartialLoadKeepsTheSavedBuildWhole() {
        PickState full = PickState.EMPTY.withTakes("Your_Toughness", 2).withChoice("Your_Lines/Core/2",
                List.of("Your_Edge")).withTakes("Your_Footing", 1).withTakes("Your_Stand", 1);
        PickState.Loadout saved = full.snapshot(id -> false);

        PickState shortOfPoints = PickState.EMPTY.withTakes("Your_Toughness", 2).withChoice("Your_Lines/Core/2",
                List.of("Your_Edge"));
        assertEquals(saved, shortOfPoints.snapshot(id -> false).writtenOver(saved, NO_NODES),
                "the takes that could not load stay saved");
        PickState belowTheSlot = PickState.EMPTY.withTakes("Your_Toughness", 2).withTakes("Your_Footing", 1);
        assertEquals(saved, belowTheSlot.snapshot(id -> false).writtenOver(saved, NO_NODES),
                "a choice passed over stays saved, in its place");

        PickState pickedSince = belowTheSlot.withTakes("Your_Stride", 1).withChoice("Your_Lines/Core/2",
                List.of("Your_Keen"));
        PickState.Loadout written = pickedSince.snapshot(id -> false).writtenOver(saved, NO_NODES);
        assertEquals(List.of("your_toughness", "your_toughness", "your_lines/core/2", "your_footing", "your_stand",
                "your_stride"), written.order(), "the saved build first, what was picked since after it");
        assertEquals(List.of("your_keen"), written.slots().get("your_lines/core/2"),
                "a slot chosen again since holds the newer choice");
    }

    /**
     * A choice node holds one face, and a reload takes the first face it meets. When the saved face was undone and
     * another face of its node taken since, the swap away writes the newer face into the saved face's place, so the
     * next load takes it rather than passing it over as the node already taken.
     */
    @Test
    void aNodeFaceTakenSinceStandsInTheSavedFacesPlace() throws IOException {
        TrackPicks.Folded folded = fold();
        Function<String, List<String>> faces = id -> {
            TrackPicks.PickDef pick = folded.byId().get(id);
            return pick == null ? List.of() : pick.otherFaces();
        };
        PickState loaded = PickState.EMPTY.withTakes("Your_Toughness", 1).withTakes("Your_Stand", 1)
                .withTakes("Your_Footing", 1);
        PickState.Loadout saved = loaded.snapshot(id -> false);

        PickState switched = loaded.withTakes("Your_Stand", -1).withTakes("Your_Rush", 1);
        assertEquals(List.of("your_toughness", "your_rush", "your_footing"),
                switched.snapshot(id -> false).writtenOver(saved, faces).order(),
                "the newer face where the saved face stood, the saved face gone");

        PickState neitherFace = PickState.EMPTY.withTakes("Your_Toughness", 1).withTakes("Your_Footing", 1);
        assertEquals(saved, neitherFace.snapshot(id -> false).writtenOver(saved, faces),
                "a saved face passed over with no other face taken stays saved");
    }

    /**
     * A build is spent again in the order its picks were made, choices and takes interleaved, so a choice opened by
     * a take (its points, a prerequisite) comes after that take. An undone choice leaves the order, and a save from
     * before the order was kept reads its slots first, lowest level first, then its takes in id order.
     */
    @Test
    void aBuildReplaysItsChoicesAndTakesInTheOrderTheyWereMade() {
        PickState made = PickState.EMPTY.withTakes("Your_Toughness", 1).withChoice("Your_Lines/Core/5",
                List.of("Your_Edge")).withTakes("Your_Footing", 1).withChoice("Your_Lines/Core/2", List.of("Your_Keen"));
        assertEquals(List.of("your_toughness", "your_lines/core/5", "your_footing", "your_lines/core/2"),
                made.snapshot(id -> false).order(), "what a swap walks: each pick where it was made");
        assertEquals(List.of("your_toughness", "your_footing"), made.snapshot(id -> false).takes());
        assertEquals(List.of("your_toughness", "your_footing", "your_lines/core/2"),
                made.withChoice("Your_Lines/Core/5", List.of()).order(), "an undone choice leaves the order");

        PickState older = new PickState(Map.of("your_lines/core/5", List.of("your_edge"), "your_lines/core/2",
                List.of("your_keen")), Map.of("your_footing", 1, "your_toughness", 1), Map.of());
        assertEquals(List.of("your_lines/core/2", "your_lines/core/5", "your_footing", "your_toughness"),
                older.order(), "no order kept: the slots by level, then the takes by id");
    }

    private static PickUndo.Entry take(String pick, long atMs) {
        return new PickUndo.Entry("your_sample", TrackKey.normalize(pick), null, List.of(), List.of(), atMs);
    }

    private static TrackPicks.Folded fold() throws IOException {
        Map<String, TrackPickAsset> picks = new LinkedHashMap<>();
        for (Map.Entry<String, String> pick : PICKS.entrySet()) {
            picks.put(pick.getKey(), decode(TrackPickAsset.CODEC, TrackPickAsset.class, pick.getKey(),
                    pick.getValue()));
        }
        TrackAsset track = decode(TrackAsset.CODEC, TrackAsset.class, "Your_Sample", "{ \"Points\": { \"PerLevel\": 1 } }");
        return TrackPicks.fold(picks, id -> null, id -> null,
                id -> "your_sample".equals(TrackKey.normalize(id)) ? track : null);
    }

    private static <T extends JsonAsset<String>> T decode(AssetBuilderCodec<String, T> codec, Class<T> type,
            String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(type, id, null);
        return codec.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }
}
