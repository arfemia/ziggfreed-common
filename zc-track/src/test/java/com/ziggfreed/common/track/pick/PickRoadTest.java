package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * A road is a function of the files alone (R724): no seed, no player, no server, no roll at the moment of levelling
 * (R653). So two folds of the same files offer the same options at every level, however the levels are asked for
 * (the next levels read ahead, a respec or a restart reopening the same offers); a pick's {@code Offers} entry writes
 * out exactly what its level offers, and an empty one puts the drawn offer back; and the main line is in every offer.
 */
class PickRoadTest {

    private static final int TOP = 89;

    private static final String CORE = "your_edge";

    private static final Map<String, String> OPTIONS = Map.of(
            "Your_Edge", "{}",
            "Your_Keen", "{ \"Bag\": \"Often\", \"From\": 10 }",
            "Your_Precision", "{ \"Bag\": \"Sometimes\", \"Turn\": 3, \"From\": 10 }",
            "Your_Focus", "{ \"From\": 30 }",
            "Your_Running_Start", "{ \"Draw\": \"Knack\", \"Bag\": \"Often\" }",
            "Your_Rebuff", "{ \"Draw\": \"Knack\", \"Bag\": \"Sometimes\" }",
            "Your_Undead", "{ \"Draw\": \"Foe\" }",
            "Your_Trork", "{ \"Draw\": \"Foe\" }",
            "Your_Void", "{ \"Draw\": \"Foe\" }",
            "Your_Goblin", "{ \"Draw\": \"Foe\" }");

    private static final String DECK = "{ \"Sizes\": { \"2\": 2, \"10\": 3, \"30\": 4 }, \"Reserved\": { \"Knack\": 1 },"
            + " \"Extra\": { \"Knack\": 3 }, \"Bag\": { \"Often\": 2, \"Sometimes\": 4 },"
            + " \"NoDrought\": { \"Often\": 3, \"Sometimes\": 6 }, \"Foes\": { \"At\": [ 35, 55, 75 ], \"Offer\": 3 } }";

    /** The sample pick, with {@code offers} (an {@code Offers} object, or null for none). */
    private static String pick(String offers) {
        return "{ \"Track\": \"Your_Swords\", \"Groups\": [ { \"Id\": \"Lines\", \"From\": 2, \"To\": " + TOP + ","
                + " \"Core\": \"Your_Edge\", \"Deck\": \"Your_Deck\", \"Options\": [ \"Your_Edge\", \"Your_Keen\","
                + " \"Your_Precision\", \"Your_Focus\", \"Your_Running_Start\", \"Your_Rebuff\", \"Your_Undead\","
                + " \"Your_Trork\", \"Your_Void\", \"Your_Goblin\" ] } ]"
                + (offers == null ? "" : ", \"Offers\": " + offers) + " }";
    }

    @Test
    void theSameFilesOfferTheSameRoadEverywhere() throws IOException {
        TrackPicks.Folded first = fold(pick(null));
        assertEquals(List.of(), first.issues(), "the sample files fold clean");
        Map<Integer, List<String>> walked = walk(first);

        TrackPicks.GroupDef ahead = lines(fold(pick(null)));
        for (int level = TOP; level >= 2; level--) {
            assertEquals(walked.get(level), ahead.offerAt(level), "level " + level + ", asked ahead, reads the same");
        }
        assertEquals(walked, walk(fold(pick(null))), "another fold of the same files (a restart, another server, a "
                + "respec) reopens the same offers");
        for (Map.Entry<Integer, List<String>> slot : walked.entrySet()) {
            List<String> offer = slot.getValue();
            assertFalse(offer.isEmpty(), "level " + slot.getKey() + " offers something");
            assertEquals(CORE, offer.get(0), "the main line leads every offer, level " + slot.getKey());
            assertEquals(offer.size(), new HashSet<>(offer).size(), "no option twice, level " + slot.getKey());
        }
    }

    @Test
    void anOffersEntryWritesOutItsLevel() throws IOException {
        Map<Integer, List<String>> drawn = walk(fold(pick(null)));
        Map<Integer, List<String>> pinned = walk(fold(pick("{ \"20\": [ \"Your_Rebuff\", \"Your_Focus\" ] }")));

        List<String> at20 = pinned.get(20);
        assertEquals(CORE, at20.get(0), "the main line leads a pinned offer too");
        assertEquals(Set.of(CORE, "your_rebuff", "your_focus"), new HashSet<>(at20),
                "the pinned level offers the core and exactly the options written for it");
        for (int level = 2; level < 20; level++) {
            assertEquals(drawn.get(level), pinned.get(level), "a level before the pinned one is drawn as before");
        }
        assertEquals(drawn, walk(fold(pick("{ \"20\": [] }"))), "an empty entry puts the drawn offer back");

        TrackPicks.Folded nowhere = fold(pick("{ \"1\": [ \"Your_Keen\" ] }"));
        assertTrue(nowhere.issues().stream().map(Finding::code).anyMatch(TrackPicks.OFFER_WITHOUT_SLOT::equals),
                "an entry at a level no group opens a slot at is reported");
    }

    private static Map<Integer, List<String>> walk(TrackPicks.Folded folded) {
        TrackPicks.GroupDef group = lines(folded);
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (int level = 2; level <= TOP; level++) {
            out.put(level, group.offerAt(level));
        }
        return out;
    }

    private static TrackPicks.GroupDef lines(TrackPicks.Folded folded) {
        TrackPicks.PickDef pick = folded.byId().get("your_swords_lines");
        assertNotNull(pick, "the pick folds");
        TrackPicks.GroupDef group = pick.group("Lines");
        assertNotNull(group, "its group folds");
        return group;
    }

    /** Every file read afresh from its JSON, then folded. */
    private static TrackPicks.Folded fold(String pickJson) throws IOException {
        Map<String, PickOptionAsset> options = new LinkedHashMap<>();
        for (Map.Entry<String, String> option : OPTIONS.entrySet()) {
            options.put(TrackKey.normalize(option.getKey()), decode(PickOptionAsset.CODEC, PickOptionAsset.class,
                    option.getKey(), option.getValue()));
        }
        PickDeckAsset deck = decode(PickDeckAsset.CODEC, PickDeckAsset.class, "Your_Deck", DECK);
        TrackAsset track = decode(TrackAsset.CODEC, TrackAsset.class, "Your_Swords", "{}");
        TrackPickAsset pick = decode(TrackPickAsset.CODEC, TrackPickAsset.class, "Your_Swords_Lines", pickJson);
        return TrackPicks.fold(Map.of("Your_Swords_Lines", pick), id -> options.get(TrackKey.normalize(id)),
                id -> "your_deck".equals(TrackKey.normalize(id)) ? deck : null,
                id -> "your_swords".equals(TrackKey.normalize(id)) ? track : null);
    }

    private static <T extends JsonAsset<String>> T decode(AssetBuilderCodec<String, T> codec, Class<T> type,
            String id, String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(type, id, null);
        return codec.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }
}
