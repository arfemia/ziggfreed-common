package com.ziggfreed.common.track.stance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.grant.Placed;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackDerived;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.validation.Finding;

/**
 * The stance rules, pure, and the seams they feed: how many slots are open (a row each, open from the start, on its key
 * pick or on its Requires), where a hold goes and why one is refused (a kind's cap, no slot free, not owned, held
 * already, no face), which held faces count (owned, in an open slot, within the cap), what a settle frees, the grant
 * fold placing a stance's picks only while their face is held and every other pick exactly as before, the live count
 * {@code ziggfreedcommon:track_pick} reads beside the owned one, and the budget the stance holds a face to.
 */
class StancesTest {

    private static final String STANCE = "your_stance";

    private static final Map<String, String> PICKS = new LinkedHashMap<>();

    static {
        PICKS.put("Your_Blade_Face", "{ \"Track\": \"Your_Sample\", \"Stance\": { \"Id\": \"Your_Stance\", \"Kind\":"
                + " \"Weapon\" }, \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": 5 } ] }");
        PICKS.put("Your_Hammer_Face", "{ \"Track\": \"Your_Sample\", \"Stance\": { \"Id\": \"Your_Stance\", \"Kind\":"
                + " \"Weapon\" }, \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": 4 } ] }");
        PICKS.put("Your_Flame_Face", "{ \"Track\": \"Your_Other\", \"Stance\": { \"Id\": \"Your_Stance\", \"Kind\":"
                + " \"Element\" }, \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Mana\", \"Add\": 5 } ] }");
        PICKS.put("Your_Blade_Step", "{ \"Track\": \"Your_Sample\", \"Stance\": { \"Id\": \"Your_Stance\", \"Face\":"
                + " \"Your_Blade_Face\" }, \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": 2 } ] }");
        PICKS.put("Your_Plain", "{ \"Track\": \"Your_Sample\", \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\":"
                + " \"Stamina\", \"Add\": 1 } ] }");
        PICKS.put("Your_Key", "{ \"Track\": \"Your_Sample\", \"TaughtOnly\": true }");
    }

    private static final String STANCE_JSON = "{ \"Slots\": [ { \"Id\": \"I\" }, { \"Id\": \"II\", \"Pick\":"
            + " \"Your_Key\" }, { \"Id\": \"III\", \"Requires\": { \"Factors\": [ { \"Factor\":"
            + " \"ziggfreedcommon:track_level\", \"Param\": \"Your_Sample\", \"Min\": 50 } ] } } ],"
            + " \"Kinds\": { \"Weapon\": { \"Max\": 1, \"WhileHeld\": [ { \"Kind\": \"Stat\", \"Stat\": \"Health\","
            + " \"Add\": -25 } ] }, \"Element\": { \"Max\": 2, \"WhileHeld\": [ { \"Kind\": \"Stat\", \"Stat\":"
            + " \"Mana\", \"Add\": -50 } ] } }, \"Budget\": { \"Health\": 7 } }";

    private static final Predicate<GateSpec> NEVER = spec -> false;
    private static final Predicate<GateSpec> ALWAYS = spec -> true;

    @Test
    void aSlotIsOpenOnItsRow() throws IOException {
        TrackStanceAsset stance = stance(STANCE_JSON);
        assertEquals(List.of("i"), keys(StanceRules.open(stance, owns(), NEVER)), "a row naming nothing is open");
        assertEquals(List.of("i", "ii"), keys(StanceRules.open(stance, owns("Your_Key"), NEVER)),
                "a row naming a pick opens on owning it");
        assertEquals(List.of("i", "ii", "iii"), keys(StanceRules.open(stance, owns("Your_Key"), ALWAYS)),
                "a row's Requires opens it, so a third slot is one more row");
        assertEquals(List.of("i", "iii"), keys(StanceRules.open(stance, owns(), ALWAYS)));
    }

    @Test
    void aHoldFillsTheLowestOpenSlotAndSaysWhyNot() throws IOException {
        TrackStanceAsset stance = stance(STANCE_JSON);
        Function<String, TrackPicks.PickDef> picks = fold()::get;
        Predicate<String> owns = owns("Your_Blade_Face", "Your_Hammer_Face", "Your_Flame_Face", "Your_Blade_Step");

        StanceRules.Decision first = StanceRules.hold(stance, HeldStances.EMPTY, "Your_Blade_Face", picks, owns, NEVER);
        assertEquals(Stances.Status.DONE, first.status());
        assertEquals("i", first.slotId(), "the lowest open slot");
        HeldStances held = HeldStances.EMPTY.with(STANCE, "i", "your_blade_face");

        assertEquals(Stances.Status.ALREADY_HELD,
                StanceRules.hold(stance, held, "YOUR_BLADE_FACE", picks, owns, NEVER).status(), "in any case");
        assertEquals(Stances.Status.KIND_FULL, StanceRules.hold(stance, held, "Your_Hammer_Face", picks, owns,
                ALWAYS).status(), "one Weapon at a time, whatever slots are open");
        assertEquals(Stances.Status.NO_SLOT, StanceRules.hold(stance, held, "Your_Flame_Face", picks, owns,
                NEVER).status(), "slot II waits on its key");
        assertEquals(Stances.Status.NOT_OWNED, StanceRules.hold(stance, held, "Your_Flame_Face", picks,
                owns("Your_Blade_Face"), NEVER).status());
        assertEquals(Stances.Status.UNKNOWN, StanceRules.hold(stance, held, "Your_Blade_Step", picks, owns,
                NEVER).status(), "a step of a face is no face");
        assertEquals(Stances.Status.UNKNOWN, StanceRules.hold(stance, held, "Your_Plain", picks, owns("Your_Plain"),
                NEVER).status(), "a pick in no stance is no face");

        StanceRules.Decision keyed = StanceRules.hold(stance, held, "Your_Flame_Face", picks,
                owns.or(owns("Your_Key")), NEVER);
        assertEquals("ii", keyed.slotId(), "the key opened slot II");
    }

    @Test
    void aHeldFaceCountsWhileOwnedInAnOpenSlotWithinItsCap() throws IOException {
        TrackStanceAsset stance = stance(STANCE_JSON);
        Function<String, TrackPicks.PickDef> picks = fold()::get;
        HeldStances held = HeldStances.EMPTY.with(STANCE, "i", "your_blade_face").with(STANCE, "ii", "your_hammer_face");
        Predicate<String> owns = owns("Your_Blade_Face", "Your_Hammer_Face", "Your_Key");

        assertEquals(List.of("your_blade_face"), faces(StanceRules.live(stance, held, picks, owns, NEVER)),
                "the second Weapon is past its kind's cap");
        assertEquals(List.of("your_blade_face"), faces(StanceRules.live(stance, held, picks,
                owns("Your_Blade_Face", "Your_Hammer_Face"), NEVER)), "slot II closed without its key");
        assertEquals(List.of(), faces(StanceRules.live(stance, HeldStances.EMPTY.with(STANCE, "i", "your_blade_face"),
                picks, owns("Your_Hammer_Face"), NEVER)), "a face held but no longer owned counts nowhere");
    }

    @Test
    void aSettleFreesWhatStoppedCounting() throws IOException {
        TrackStanceAsset stance = stance(STANCE_JSON);
        Map<String, TrackPicks.PickDef> folded = fold();
        Function<String, TrackPicks.PickDef> picks = folded::get;
        HeldStances twoKinds = HeldStances.EMPTY.with(STANCE, "i", "your_blade_face").with(STANCE, "ii",
                "your_flame_face");

        assertEquals(List.of(), slots(StanceRules.freed(stance, twoKinds, picks,
                owns("Your_Blade_Face", "Your_Flame_Face", "Your_Key"), NEVER)), "all still counts");
        assertEquals(List.of("ii"), slots(StanceRules.freed(stance, twoKinds, picks,
                owns("Your_Blade_Face", "Your_Flame_Face"), NEVER)), "its slot closed when its key went");
        assertEquals(List.of("i"), slots(StanceRules.freed(stance, twoKinds, picks,
                owns("Your_Flame_Face", "Your_Key"), NEVER)), "a face no longer owned (undone, respecced)");

        HeldStances twoWeapons = HeldStances.EMPTY.with(STANCE, "i", "your_blade_face").with(STANCE, "ii",
                "your_hammer_face");
        assertEquals(List.of("ii"), slots(StanceRules.freed(stance, twoWeapons, picks,
                owns("Your_Blade_Face", "Your_Hammer_Face", "Your_Key"), NEVER)), "past a lowered cap, the later slot");

        HeldStances gone = HeldStances.EMPTY.with(STANCE, "i", "your_switched_off").with(STANCE, "iv",
                "your_blade_face");
        assertEquals(List.of("iv"), slots(StanceRules.freed(stance, gone, picks, owns("Your_Blade_Face"), NEVER)),
                "a slot no row names is freed; a pick switched off waits, unread, in its open slot");
    }

    /**
     * The grant fold places a stance's picks only while their face counts, and what a held face's kind adds beside
     * them; every pick in no stance is placed exactly as before, held or not, and the old four-argument fold is the
     * fold holding nothing.
     */
    @Test
    void theGrantFoldPlacesAStancesPicksOnlyWhileHeld() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        TrackAsset sample = track("Your_Sample");
        TrackAsset other = track("Your_Other");
        TrackRecord onSample = new TrackRecord(0L, 0.0, 0, 0, Map.of()).withPicks(PickState.EMPTY
                .withTakes("Your_Blade_Face", 1).withTakes("Your_Hammer_Face", 1).withTakes("Your_Blade_Step", 1)
                .withTakes("Your_Plain", 1));
        TrackRecord onOther = new TrackRecord(0L, 0.0, 0, 0, Map.of()).withPicks(PickState.EMPTY
                .withTakes("Your_Flame_Face", 1));
        Function<String, TrackRecord> records = id -> "your_sample".equals(id) ? onSample
                : "your_other".equals(id) ? onOther : null;
        TrackDerived.GrantFilter all = (grant, trackId, categoryId) -> true;

        List<Placed> none = TrackDerived.placements(picks, List.of(sample, other), records, all);
        assertEquals(Set.of("zigtrack:your_sample:your_plain"), keysOf(none),
                "holding nothing, a stance's picks place nothing and a plain pick places as it always did");
        assertEquals(none, TrackDerived.placements(picks, List.of(sample, other), records, all, StanceView.NONE));

        HeldStances record = HeldStances.EMPTY.with(STANCE, "i", "your_blade_face").with(STANCE, "ii", "your_hammer_face");
        TrackStanceAsset stance = stance(STANCE_JSON);
        StanceView view = StanceView.of(record, id -> STANCE.equals(TrackKey.normalize(id)) ? stance : null, picks,
                owns("Your_Blade_Face", "Your_Hammer_Face", "Your_Blade_Step", "Your_Flame_Face", "Your_Key"));
        assertTrue(view.holds("Your_Blade_Face"));
        assertFalse(view.holds("Your_Hammer_Face"), "owned and in a slot, but past its kind's cap");
        List<Placed> held = TrackDerived.placements(picks, List.of(sample, other), records, all, view);
        assertEquals(Set.of("zigtrack:your_sample:your_plain", "zigtrack:your_sample:your_blade_face",
                "zigtrack:your_sample:your_blade_step", TrackDerived.heldKey("Your_Sample", "Your_Blade_Face")),
                keysOf(held), "the held face, its step, and what holding it costs; the unheld face nothing");
        StatGrant cost = (StatGrant) held.stream()
                .filter(p -> p.key().equals("zigtrack:your_sample:your_blade_face:held")).findFirst().orElseThrow()
                .grant();
        assertEquals(-25.0, cost.add(), "a negative grant while held");

        List<Placed> plainNone = new ArrayList<>();
        List<Placed> plainHeld = new ArrayList<>();
        none.stream().filter(p -> p.key().endsWith(":your_plain")).forEach(plainNone::add);
        held.stream().filter(p -> p.key().endsWith(":your_plain")).forEach(plainHeld::add);
        assertEquals(plainNone, plainHeld, "a pick in no stance places the same whatever is held");
    }

    /**
     * {@code ziggfreedcommon:track_pick} counts what lives, {@code track_pick_owned} what is owned: a track with no
     * pick in a stance counts exactly as before and never reads what is held.
     */
    @Test
    void theLiveCountReadsHeldFacesAndOnlyWhereAStanceIs() throws IOException {
        Map<String, TrackPicks.PickDef> picks = fold();
        List<TrackPicks.PickDef> onSample = new ArrayList<>();
        picks.values().stream().filter(p -> p.trackId().equals("your_sample")).forEach(onSample::add);
        PickState state = PickState.EMPTY.withTakes("Your_Blade_Face", 1).withTakes("Your_Blade_Step", 1)
                .withTakes("Your_Plain", 2);

        assertEquals(0, TrackPicks.liveCount(onSample, state, "Your_Blade_Step", p -> false), "its face not held");
        assertEquals(1, TrackPicks.liveCount(onSample, state, "Your_Blade_Step",
                p -> "your_blade_face".equals(p.asset().stanceFace())), "its face held");
        assertEquals(1, state.count("Your_Blade_Step"), "owned for good either way");
        assertEquals(2, TrackPicks.liveCount(onSample, state, "Your_Plain", p -> false),
                "a pick in no stance counts exactly as taken");

        List<TrackPicks.PickDef> plainOnly = new ArrayList<>();
        plainOnly.add(picks.get("your_plain"));
        assertEquals(state.count("Your_Plain"), TrackPicks.liveCount(plainOnly, state, "Your_Plain", p -> {
            throw new AssertionError("a track with no pick in a stance never asks what is held");
        }));
    }

    @Test
    void aFaceIsHeldToItsStancesBudget() throws IOException {
        TrackStanceAsset stance = stance(STANCE_JSON);
        Map<String, TrackPicks.PickDef> picks = fold();
        assertEquals(List.of(), codes(StanceValidator.audit(List.of(stance), picks, id -> stance)),
                "five and two health is the face's budget of seven");

        TrackStanceAsset tight = stance(STANCE_JSON.replace("\"Health\": 7", "\"Health\": 6"));
        assertEquals(List.of(StanceValidator.STANCE_FACE_OVER_BUDGET),
                codes(StanceValidator.audit(List.of(tight), picks, id -> tight)), "one point past it");

        Map<String, TrackPicks.PickDef> orphaned = new LinkedHashMap<>(picks);
        orphaned.remove("your_key");
        List<String> found = codes(StanceValidator.audit(List.of(stance), orphaned, id -> null));
        assertTrue(found.contains(StanceValidator.UNKNOWN_SLOT_PICK), "a slot opening on no pick: " + found);
        assertTrue(found.contains(StanceValidator.UNKNOWN_STANCE), "a pick naming no live stance: " + found);
    }

    @Test
    void aHeldRecordKeepsOneFacePerSlotAndOneSlotPerFace() {
        HeldStances held = HeldStances.EMPTY.with("Your_Stance", "I", "Your_Blade_Face")
                .with("Your_Stance", "II", "Your_Blade_Face");
        assertEquals(List.of(new HeldStances.Held(STANCE, "ii", "your_blade_face")), held.rows(),
                "holding a face again moves it");
        HeldStances replaced = held.with("Your_Stance", "II", "Your_Flame_Face");
        assertEquals("your_flame_face", replaced.faceIn(STANCE, "II"), "a slot holds one face");
        assertEquals(HeldStances.EMPTY, replaced.without(STANCE, "ii"));
        assertEquals(replaced, replaced.without(STANCE, "iii"), "emptying an empty slot changes nothing");
    }

    // ==================== fixtures ====================

    private static Map<String, TrackPicks.PickDef> fold() throws IOException {
        Map<String, TrackPickAsset> picks = new LinkedHashMap<>();
        for (Map.Entry<String, String> pick : PICKS.entrySet()) {
            picks.put(pick.getKey(), decode(TrackPickAsset.CODEC, TrackPickAsset.class, pick.getKey(),
                    pick.getValue()));
        }
        TrackAsset sample = track("Your_Sample");
        TrackAsset other = track("Your_Other");
        return TrackPicks.fold(picks, id -> null, id -> switch (TrackKey.normalize(id)) {
            case "your_sample" -> sample;
            case "your_other" -> other;
            default -> null;
        }).byId();
    }

    private static TrackAsset track(String id) throws IOException {
        return decode(TrackAsset.CODEC, TrackAsset.class, id, "{ }");
    }

    private static TrackStanceAsset stance(String json) throws IOException {
        return decode(TrackStanceAsset.CODEC, TrackStanceAsset.class, "Your_Stance", json);
    }

    private static <T extends JsonAsset<String>> T decode(AssetBuilderCodec<String, T> codec, Class<T> type,
            String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(type, id, null);
        return codec.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }

    private static Predicate<String> owns(String... ids) {
        Set<String> owned = new HashSet<>();
        for (String id : ids) {
            owned.add(TrackKey.normalize(id));
        }
        return id -> owned.contains(TrackKey.normalize(id));
    }

    private static List<String> keys(List<SlotRow> rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(row -> out.add(row.key()));
        return out;
    }

    private static List<String> faces(List<StanceRules.LiveFace> live) {
        List<String> out = new ArrayList<>();
        live.forEach(face -> out.add(face.faceId()));
        return out;
    }

    private static List<String> slots(List<HeldStances.Held> rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(row -> out.add(row.slot()));
        return out;
    }

    private static Set<String> keysOf(List<Placed> placed) {
        Set<String> out = new TreeSet<>();
        placed.forEach(p -> out.add(p.key()));
        return out;
    }

    private static List<String> codes(List<Finding> findings) {
        List<String> out = new ArrayList<>();
        findings.forEach(f -> out.add(f.code()));
        return out;
    }
}
