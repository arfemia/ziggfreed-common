package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.ModGateFold;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * A tree's files as data (SK7a0): a generator writes a family of ordinary picks, each row under its own skeleton
 * ({@code Base} with tokens), a value keeping its type; a hand-written file of a generated id replaces it; a skeleton
 * switched off (in its file, or by an owner entry) takes every pick under it away, while a skeleton itself is never a
 * pick; and a batch of takes is one undo, however long.
 */
class TrackPickGeneratorTest {

    private static final String TREE = "{ \"Abstract\": true }";
    private static final String SWORDS_TREE = "{ \"Abstract\": true, \"Track\": \"Your_Swords\" }";
    private static final String AXES_TREE = "{ \"Abstract\": true, \"Track\": \"Your_Axes\" }";

    private static final String HARDY = "{ \"Base\": \"Your_Tree_{Skill}\", \"IdPattern\": \"Your_{Skill}_Hardy\","
            + " \"ForEach\": [ { \"Values\": [ { \"Skill\": \"Swords\", \"Add\": 2 }, { \"Skill\": \"Axes\", \"Add\": 3 } ] } ],"
            + " \"Child\": { \"Text\": { \"TitleKey\": \"yourmod.tree.hardy\","
            + " \"TextArgs\": { \"Title\": [ \"@key:yourmod.skill.{Skill}\" ] } },"
            + " \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 3 },"
            + " \"Grants\": [ { \"Kind\": \"Stat\", \"Stat\": \"Stamina\", \"Add\": \"{Add}\" } ] } }";

    @Test
    void aGeneratorWritesOrdinaryPicksUnderEachRowsOwnSkeleton() throws IOException {
        Map<String, TrackPickAsset> authored = skeletons();
        TrackPickGenerators.Expansion expansion = TrackPickGenerators.expand(authored, Set.of(),
                List.of(generator("Your_Hardy", HARDY)), null);
        assertEquals(List.of(), expansion.issues(), "a family whose every row has a Base writes cleanly");
        TrackPickAsset swords = expansion.picks().get("your_swords_hardy");
        assertNotNull(swords, "one pick per row, its id spelled by IdPattern");
        assertEquals("Your_Swords", swords.trackId(), "the track comes from the row's own skeleton");
        assertEquals("your_tree_swords", TrackKey.normalize(swords.parentId()), "the Base is filled per row");
        assertFalse(swords.isAbstract(), "a pick under a skeleton is a real pick");
        assertEquals(1, swords.costPoints());
        assertEquals(3, swords.repeatMax());
        assertEquals(2.0, ((StatGrant) swords.grants().get(0)).add(), "a one-token value keeps its number type");
        assertEquals(List.of("@key:yourmod.skill.Swords"), swords.getText().titleArgs(),
                "the family's one key, the row's name nested into it");
        assertEquals("Your_Axes", expansion.picks().get("your_axes_hardy").trackId());

        TrackPickGenerators.Expansion refusedBase = TrackPickGenerators.expand(Map.of(), Set.of("your_tree_swords",
                "your_tree_axes"), List.of(generator("Your_Hardy", HARDY)), null);
        assertTrue(refusedBase.picks().isEmpty() && refusedBase.issues().isEmpty(),
                "a family over a skeleton the mod gate refused is absent and silent");
        TrackPickGenerators.Expansion missingBase = TrackPickGenerators.expand(Map.of(), Set.of(),
                List.of(generator("Your_Hardy", HARDY)), null);
        assertEquals(TrackPickGenerators.UNKNOWN_PICK_BASE, missingBase.issues().get(0).code(),
                "a Base nobody ships is named");
    }

    @Test
    void aHandWrittenFileOfAGeneratedIdWinsAndAnOwnerKeepsItsLineage() throws IOException {
        TrackPickConfig config = TrackPickConfig.getInstance();
        TrackPickConfig.Snapshot before = config.snapshot();
        try {
            Map<String, TrackPickAsset> authored = skeletons();
            authored.put("your_axes_hardy", child("Your_Axes_Hardy", "Your_Tree_Axes", authored.get("your_tree_axes"),
                    "{ \"Parent\": \"Your_Tree_Axes\", \"Cost\": { \"Points\": 2 } }"));
            config.mergePackLayer(new ModGateFold<>(TrackPickConfig.MOD_GATE_STORE, authored, Map.of()));
            config.mergeGenerators(Map.of("your_hardy", generator("Your_Hardy", HARDY)));
            assertEquals(2, config.resolve("your_axes_hardy").costPoints(), "the hand-written file replaces it");
            assertEquals(1, config.resolve("your_swords_hardy").costPoints(), "the rest of the family stands");
            assertTrue(config.generatorIssues().stream().anyMatch(f -> f.code().equals(
                    TrackPickGenerators.GENERATED_ID_SHADOWED) && f.sourceId().equals("your_axes_hardy")),
                    "the replacement is told, at INFO");

            TrackPickAsset below = config.resolveBelowOwner("your_tree_swords");
            TrackPickAsset off = child("Your_Tree_Swords", "Your_Tree_Swords", below, "{ \"Enabled\": false }");
            config.mergeOwnerLayer(Map.of("your_tree_swords", off));
            assertTrue(config.resolve("your_tree_swords").isAbstract(), "an owner entry keeps its file's Abstract");
            assertEquals("your_tree", TrackKey.normalize(config.resolve("your_tree_swords").parentId()),
                    "and its Parent");

            TrackPicks.Folded folded = TrackPicks.fold(config.all(), id -> null, id -> null, TrackPickGeneratorTest::track);
            assertNull(folded.byId().get("your_swords_hardy"), "an owner switching the Swords skeleton off takes "
                    + "every Swords pick away, though the pick itself was decoded switched on");
            assertNotNull(folded.byId().get("your_axes_hardy"), "the Axes tree stands");
            assertNull(folded.byId().get("your_tree_axes"), "a skeleton is never a pick");
        } finally {
            config.restore(before);
        }
    }

    /**
     * The engine re-decodes a parent's children when a pack overrides the parent ({@code AssetStore.loadAllChildren}),
     * and {@code Enabled} is inherited: a child written without it reads its skeleton's.
     */
    @Test
    void aSkeletonSwitchedOffInItsFileSwitchesItsChildrenOff() throws IOException {
        TrackPickAsset tree = decode("Your_Tree", null, null, "{ \"Abstract\": true, \"Enabled\": false }");
        TrackPickAsset swords = child("Your_Tree_Swords", "Your_Tree", tree, SWORDS_TREE);
        TrackPickAsset node = child("Your_Swords_Edge", "Your_Tree_Swords", swords,
                "{ \"Cost\": { \"Points\": 1 }, \"Repeat\": { \"Max\": 3 } }");
        assertFalse(node.isEnabled(), "a child inherits its skeleton's Enabled");
        TrackPickAsset forced = child("Your_Swords_Keen", "Your_Tree_Swords", swords,
                "{ \"Enabled\": true, \"Cost\": { \"Points\": 1 } }");
        Map<String, TrackPickAsset> picks = new LinkedHashMap<>();
        picks.put("your_tree", tree);
        picks.put("your_tree_swords", swords);
        picks.put("your_swords_edge", node);
        picks.put("your_swords_keen", forced);
        TrackPicks.Folded folded = TrackPicks.fold(picks, id -> null, id -> null, TrackPickGeneratorTest::track);
        assertTrue(folded.byId().isEmpty(), "one switched-off skeleton takes the whole tree, even a pick that writes "
                + "Enabled itself");
    }

    @Test
    void aBatchOfTakesIsOneUndo() {
        UUID player = UUID.randomUUID();
        try {
            long at = 1_000_000L;
            for (int i = 0; i < PickUndo.MAX_ENTRIES; i++) {
                PickUndo.record(player, new PickUndo.Entry("your_swords", "your_older_" + i, null, List.of(),
                        List.of(), at));
            }
            List<String> starter = new ArrayList<>();
            PickState spent = PickState.EMPTY;
            for (int i = 0; i < 99; i++) {
                String pick = "your_rank_" + (i % 33);
                starter.add(pick);
                spent = spent.withTakes(pick, 1);
            }
            PickUndo.record(player, PickUndo.Entry.batch("your_swords", starter, false, at + 1_000L));
            PickUndo.Entry batch = PickUndo.pop(player, at + 2_000L);
            assertNotNull(batch);
            assertEquals(99, batch.batch().size(), "the whole batch is one entry, never 35 of its takes pushed out");
            PickState back = PickUndo.undoBatch(spent, batch);
            assertNotNull(back);
            assertEquals(0, back.size(), "one undo gives every take of the batch back");
            assertEquals("your_older_63", PickUndo.pop(player, at + 2_000L).pickId(),
                    "the picks before the batch are still in the window, one entry pushed out at most");

            PickState remembered = PickState.EMPTY.withTakes("your_edge", 1).withTakes("your_keen", 1)
                    .withRememberedTakes(List.of("your_stand"));
            PickUndo.Entry respent = PickUndo.Entry.batch("your_swords", List.of("your_edge", "your_keen"), true, at);
            assertEquals(List.of("your_edge", "your_keen", "your_stand"),
                    PickUndo.undoBatch(remembered, respent).rememberedTakes(),
                    "re-spent remembered takes go back to the head of what is remembered, in their order");
            assertNull(PickUndo.undoBatch(PickState.EMPTY, respent), "a batch no longer held undoes nothing");
        } finally {
            PickUndo.close(player);
        }
    }

    private static TrackAsset track(String id) {
        String key = TrackKey.normalize(id);
        if (!key.equals("your_swords") && !key.equals("your_axes")) {
            return null;
        }
        try {
            return TrackAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString("{ \"Points\": { \"PerLevel\": 1 } }"),
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackAsset.class, id, null)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, TrackPickAsset> skeletons() throws IOException {
        Map<String, TrackPickAsset> out = new LinkedHashMap<>();
        TrackPickAsset tree = decode("Your_Tree", null, null, TREE);
        out.put("your_tree", tree);
        out.put("your_tree_swords", child("Your_Tree_Swords", "Your_Tree", tree, SWORDS_TREE));
        out.put("your_tree_axes", child("Your_Tree_Axes", "Your_Tree", tree, AXES_TREE));
        return out;
    }

    private static TrackPickAsset child(String id, String parentId, TrackPickAsset parent, String json)
            throws IOException {
        return decode(id, parentId, parent, json);
    }

    private static TrackPickAsset decode(String id, String parentId, TrackPickAsset parent, String json)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackPickAsset.class, id, parentId);
        return parent == null
                ? TrackPickAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data))
                : TrackPickAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                        new AssetExtraInfo<>(data));
    }

    private static TrackPickGeneratorAsset generator(String id, String json) throws IOException {
        return TrackPickGeneratorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(TrackPickGeneratorAsset.class, id, null)));
    }
}
