package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.PrestigeReset;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * A respec of a kind of pick, pure ({@code PickService.planKind}): every pick of its {@code ResetsAs} kind on every
 * track leaves but what was only ever taught and what its {@code Keep} names; every other kind, a pick the fold does
 * not know and a track it clears nothing on stay exactly as they were; each cleared take's price today (its growth
 * included) comes back at the respec's shares, the points with the takes; and a build in use stays linked unless a
 * cleared pick could have been saved in it.
 */
class KindRespecTest {

    private static final Map<String, String> PICKS = new LinkedHashMap<>();

    static {
        // Two steps of the kind on two tracks, priced in two wallets and an item.
        PICKS.put("Your_Blade_First", "{ \"Track\": \"Your_Blades\", \"ResetsAs\": \"Mastery\", \"Cost\": { \"Price\":"
                + " { \"Currencies\": { \"Your_Point\": 1, \"Your_Coin\": 250 },"
                + " \"Items\": [ { \"Item\": \"Your_Essence\", \"Count\": 3 } ] } } }");
        PICKS.put("Your_Flame_First", "{ \"Track\": \"Your_Flames\", \"ResetsAs\": \"Mastery\", \"Cost\": { \"Price\":"
                + " { \"Currencies\": { \"Your_Point\": 2, \"Your_Coin\": 1000 } } } }");
        // A rank of the kind that grows, kept by the respec's Keep.
        PICKS.put("Your_Blade_Rank", "{ \"Track\": \"Your_Blades\", \"ResetsAs\": \"Mastery\", \"Repeat\": { \"Max\": 0 },"
                + " \"Cost\": { \"Price\": { \"Currencies\": { \"Your_Coin\": 500 } }, \"Scaling\": { \"Multiplier\": 1.1 } } }");
        // A rank of the kind that grows and is not kept.
        PICKS.put("Your_Blade_Mark", "{ \"Track\": \"Your_Blades\", \"ResetsAs\": \"Mastery\", \"Repeat\": { \"Max\": 0 },"
                + " \"Cost\": { \"Price\": { \"Currencies\": { \"Your_Coin\": 100 } }, \"Scaling\": { \"Multiplier\": 2 } } }");
        // Only ever taught: kept whatever its kind.
        PICKS.put("Your_Slot_Key", "{ \"Track\": \"Your_Blades\", \"ResetsAs\": \"Mastery\", \"TaughtOnly\": true }");
        // Another kind: a tree pick bought with points, which a saved build may hold.
        PICKS.put("Your_Edge", "{ \"Track\": \"Your_Blades\", \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 5 } }");
        // A free pick of the kind, which a saved build may hold.
        PICKS.put("Your_Flame_Free", "{ \"Track\": \"Your_Flames\", \"ResetsAs\": \"Mastery\" }");
    }

    private static final String RESPEC = "{ \"ResetsAs\": \"Mastery\", \"Keep\": [ \"Your_*_Rank\" ],"
            + " \"Refund\": { \"Currencies\": { \"Your_Coin\": 0.8 }, \"Items\": 1 } }";

    @Test
    void itClearsItsKindOnEveryTrackAndKeepsTheRest() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        TrackPickRespecAsset respec = respec(RESPEC);
        PickState blades = PickState.EMPTY.withTakes("Your_Blade_First", 1).withTakes("Your_Blade_Rank", 3)
                .withTakes("Your_Blade_Mark", 2).withTakes("Your_Slot_Key", 1).withTakes("Your_Edge", 4)
                .withTakes("Your_Gone", 1);
        PickState flames = PickState.EMPTY.withTakes("Your_Flame_First", 1);
        PickState untouched = PickState.EMPTY.withTakes("Your_Edge", 2);
        Map<String, PickState> states = new LinkedHashMap<>();
        states.put("Your_Blades", blades);
        states.put("Your_Flames", flames);
        states.put("Your_Other", untouched);

        PickService.KindPlan plan = PickService.planKind(respec, states, picks::get);

        assertEquals(List.of("your_blades", "your_flames"), List.copyOf(plan.after().keySet()),
                "a track it clears nothing on is left out");
        PickState after = plan.after().get("your_blades");
        assertEquals(0, after.takesOf("Your_Blade_First"), "a step of the kind leaves");
        assertEquals(0, after.takesOf("Your_Blade_Mark"));
        assertEquals(3, after.takesOf("Your_Blade_Rank"), "a pick its Keep names stays");
        assertEquals(1, after.takesOf("Your_Slot_Key"), "what was only ever taught stays");
        assertEquals(4, after.takesOf("Your_Edge"), "a pick of another kind stays");
        assertEquals(1, after.takesOf("Your_Gone"), "a pick the fold does not know stays");
        assertEquals(0, plan.after().get("your_flames").takesOf("Your_Flame_First"), "on every track");
        assertEquals(1 + 2 + 1, plan.cleared(), "every take cleared, counted");
        assertEquals(blades, plan.before().get("your_blades"), "what it found, for its undo");
    }

    @Test
    void itGivesBackEachTakesPriceTodayAtItsShares() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        PickState blades = PickState.EMPTY.withTakes("Your_Blade_First", 1).withTakes("Your_Blade_Mark", 3);
        PickState flames = PickState.EMPTY.withTakes("Your_Flame_First", 1);
        Map<String, PickState> states = new LinkedHashMap<>();
        states.put("Your_Blades", blades);
        states.put("Your_Flames", flames);

        PickService.KindPlan plan = PickService.planKind(respec(RESPEC), states, picks::get);

        // Coins paid: 250 (blade first) + 100 + 200 + 400 (three marks at x2 a take) + 1000 (flame first) = 1950.
        assertEquals((long) Math.floor(1950 * 0.8), plan.refund().amountOf("your_coin"),
                "four fifths of every coin, each take at its own price");
        assertEquals(1L + 2L, plan.refund().amountOf("your_point"), "a wallet the Refund does not name: all of it");
        assertEquals(3, plan.refund().items().get(0).count(), "every item back at an item share of 1");
    }

    @Test
    void aBuildInUseStaysLinkedUnlessAClearedPickCouldHaveBeenSaved() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        TrackPickRespecAsset respec = respec(RESPEC);
        PickState priced = PickState.EMPTY.withTakes("Your_Blade_First", 1).withTakes("Your_Edge", 2)
                .withActiveLoadout(1);
        PickState free = PickState.EMPTY.withTakes("Your_Flame_Free", 1).withActiveLoadout(2);
        Map<String, PickState> states = new LinkedHashMap<>();
        states.put("Your_Blades", priced);
        states.put("Your_Flames", free);

        PickService.KindPlan plan = PickService.planKind(respec, states, picks::get);
        assertEquals(1, plan.after().get("your_blades").activeLoadout(),
                "a priced pick is never in a saved build, so clearing it leaves the build in use linked");
        assertEquals(0, plan.after().get("your_flames").activeLoadout(),
                "a free pick a build could hold unlinks the build in use, as a respec does");
    }

    @Test
    void aRespecOfNoKindClearsNothingAndItsAuditSaysWhy() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        TrackPickRespecAsset none = respec("{ \"ResetsAs\": \"Everything\" }");
        assertNull(none.kind());
        Map<String, PickState> states = Map.of("Your_Blades", PickState.EMPTY.withTakes("Your_Blade_First", 1));
        assertEquals(0, PickService.planKind(none, states, picks::get).cleared());

        TrackPickRespecAsset shares = respec("{ \"ResetsAs\": \"Mastery\", \"Keep\": [ \"Your_Typo_*\" ],"
                + " \"Refund\": { \"Currencies\": { \"Your_Coin\": 1.5 } } }");
        assertEquals(1.0, shares.currencyShare("YOUR_COIN"), "a share past 1 is held to 1, any case");
        List<String> codes = codes(PickRespecValidator.audit(List.of(none, shares), picks));
        assertTrue(codes.contains(PickRespecValidator.RESPEC_WITHOUT_KIND), codes.toString());
        assertTrue(codes.contains(PickRespecValidator.RESPEC_SHARE_OUT_OF_RANGE), codes.toString());
        assertTrue(codes.contains(PickRespecValidator.RESPEC_KEEPS_NOTHING_KNOWN), codes.toString());
        assertFalse(codes.contains(PickRespecValidator.RESPEC_CLEARS_NOTHING), "a pick of the kind is there to clear");
    }

    @Test
    void aPickReadsItsKindAsAPrestigeDoes() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        TrackPickRespecAsset tree = respec("{ \"ResetsAs\": \"Picks\" }");
        assertTrue(tree.clears(picks.get("your_edge").asset()), "a pick naming no kind and no trainer is Picks");
        assertEquals(PrestigeReset.MASTERY, picks.get("your_blade_first").asset().resetsAs());
        assertFalse(tree.clears(picks.get("your_blade_first").asset()));
    }

    // ==================== fixtures ====================

    private static Map<String, TrackPicks.PickDef> fold() throws IOException {
        Map<String, TrackPickAsset> picks = new LinkedHashMap<>();
        for (Map.Entry<String, String> pick : PICKS.entrySet()) {
            picks.put(pick.getKey(), decode(TrackPickAsset.CODEC, TrackPickAsset.class, pick.getKey(),
                    pick.getValue()));
        }
        Set<String> tracks = Set.of("your_blades", "your_flames", "your_other");
        Map<String, TrackAsset> byId = new LinkedHashMap<>();
        for (String id : tracks) {
            byId.put(id, decode(TrackAsset.CODEC, TrackAsset.class, id, "{ }"));
        }
        return TrackPicks.fold(picks, id -> null, id -> byId.get(TrackKey.normalize(id))).byId();
    }

    private static TrackPickRespecAsset respec(String json) throws IOException {
        return decode(TrackPickRespecAsset.CODEC, TrackPickRespecAsset.class, "Your_Respec", json);
    }

    private static <T extends JsonAsset<String>> T decode(AssetBuilderCodec<String, T> codec, Class<T> type,
            String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(type, id, null);
        return codec.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }
}
