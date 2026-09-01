package com.ziggfreed.common.world.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.ziggfreed.common.world.record.BlockRecordSection;

/**
 * {@link BlockStash#CODEC} / {@link StashPile#CODEC} round-trips: the payload a chunk save and
 * load puts through the wire. Insertion order is load-bearing (a pile's {@code Items} is a drain
 * order), so it is pinned across the round trip, and two stashes riding one section must stay
 * independent.
 *
 * <p>The {@code Unique} leaf delegates to the engine's own {@code ItemStack} codec through a
 * deferred reference, and a bare unit-test JVM cannot initialize the {@code ItemStack} class at
 * all (its codec chain forces {@code Item}'s validator statics, which need the engine log manager
 * - the boundary {@code NativeLootServiceTest} documents). So no fixture here authors
 * {@code Unique}: that the deferred reference defers is pinned by {@code DeferredCodecTest}, the
 * delegation being verbatim is that type's contract, and the stack's metadata riding along
 * byte-identically is the engine codec's own (its {@code Metadata} key carries the raw document
 * verbatim). What a real stack does through this leaf lands in in-game smoke.
 */
class BlockStashCodecTest {

    private static BlockStash roundTrip(BlockStash original) {
        ExtraInfo info = new ExtraInfo();
        return BlockStash.CODEC.decode(BlockStash.CODEC.encode(original, info), info);
    }

    private static BlockStash decode(String json) throws IOException {
        return BlockStash.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void everyAuthoredLeafSurvivesTheRoundTrip() {
        BlockStash stash = new BlockStash();
        stash.setOwner("11111111-2222-3333-4444-555555555555");
        stash.setProgressGameTime(12_345L);
        stash.setLastGameTime(67_890L);
        stash.setTag("some_layout");
        StashPile pile = stash.ensurePile("main");
        pile.setOwner("11111111-2222-3333-4444-555555555555");
        pile.itemsMutable().put("Ingredient_Meat", 3);

        BlockStash restored = roundTrip(stash);

        assertEquals("11111111-2222-3333-4444-555555555555", restored.getOwner());
        assertEquals(12_345L, restored.getProgressGameTime());
        assertEquals(67_890L, restored.getLastGameTime());
        assertEquals("some_layout", restored.getTag());
        StashPile restoredPile = restored.pile("main");
        assertEquals("11111111-2222-3333-4444-555555555555", restoredPile.getOwner());
        assertEquals(Map.of("Ingredient_Meat", 3), restoredPile.getItems());
    }

    @Test
    void anEmptyStashDecodesToAllNullLeaves() throws IOException {
        BlockStash stash = decode("{}");
        assertNull(stash.getOwner());
        assertNull(stash.getPiles());
        assertNull(stash.getProgressGameTime());
        assertNull(stash.getLastGameTime());
        assertNull(stash.getTag());
    }

    @Test
    void anEmptyPileDecodesToAllNullLeaves() throws IOException {
        StashPile pile = StashPile.CODEC.decodeJson(RawJsonReader.fromJsonString("{}"), new ExtraInfo());
        assertNull(pile.getOwner());
        assertNull(pile.getItems());
        assertNull(pile.getUnique());
        assertNull(pile.getPendingCycles());
    }

    /** Items is a drain order: what went in first must still come first after a save and load. */
    @Test
    void itemInsertionOrderSurvivesTheRoundTrip() {
        BlockStash stash = new BlockStash();
        Map<String, Integer> items = stash.ensurePile("main").itemsMutable();
        items.put("Ingredient_Meat", 2);
        items.put("Ingredient_Carrot", 5);
        items.put("Ingredient_Mushroom", 1);
        items.put("Ingredient_Salt", 9);

        BlockStash restored = roundTrip(stash);

        assertEquals(List.of("Ingredient_Meat", "Ingredient_Carrot", "Ingredient_Mushroom", "Ingredient_Salt"),
                List.copyOf(restored.pile("main").getItems().keySet()));
    }

    @Test
    void pileInsertionOrderSurvivesTheRoundTrip() {
        BlockStash stash = new BlockStash();
        stash.ensurePile("first").setOwner("owner-a");
        stash.ensurePile("second").setOwner("owner-b");
        stash.ensurePile("third").setOwner("owner-a");

        BlockStash restored = roundTrip(stash);

        assertEquals(List.of("first", "second", "third"),
                List.copyOf(restored.getPiles().keySet()));
    }

    /**
     * The engine's map codec drops an entry whose encoded value is an EMPTY document, so a pile
     * with no authored leaf does not survive a save. That is the storage costing nothing when it
     * says nothing, and it is why a meaningful pile always records at least its owner; pinned here
     * so a consumer learns it from a test rather than from a vanished pile.
     */
    @Test
    void aPileWithNoAuthoredLeafDoesNotSurviveTheRoundTrip() {
        BlockStash stash = new BlockStash();
        stash.ensurePile("empty");
        stash.ensurePile("kept").setOwner("owner-a");

        BlockStash restored = roundTrip(stash);

        assertNull(restored.pile("empty"));
        assertEquals("owner-a", restored.pile("kept").getOwner());
    }

    @Test
    void pendingCyclesSurviveTheRoundTrip() {
        BlockStash stash = new BlockStash();
        StashPile pile = stash.ensurePile("main");
        pile.pendingCyclesMutable().put("first_kind", 4);
        pile.pendingCyclesMutable().put("second_kind", 1);

        StashPile restored = roundTrip(stash).pile("main");

        assertEquals(Map.of("first_kind", 4, "second_kind", 1), restored.getPendingCycles());
        assertEquals(List.of("first_kind", "second_kind"), List.copyOf(restored.getPendingCycles().keySet()));
    }

    @Test
    void ensurePileHandsBackTheSamePileEachTime() {
        BlockStash stash = new BlockStash();
        StashPile first = stash.ensurePile("main");
        assertSame(first, stash.ensurePile("main"));
        assertSame(first, stash.pile("main"));
        assertNull(stash.pile("other"));
    }

    /** The stash payload riding the generic record section: neighbours never bleed together. */
    @Test
    void twoStashesInOneSectionStayIndependent() {
        BuilderCodec<BlockRecordSection<BlockStash>> codec =
                BlockRecordSection.buildCodec(BlockStash.CODEC);
        BlockRecordSection<BlockStash> section = codec.getDefaultValue();

        BlockStash filled = new BlockStash();
        filled.setTag("alpha");
        filled.ensurePile("main").itemsMutable().put("Ingredient_Meat", 2);
        section.put(ChunkUtil.indexBlock(3, 4, 5), filled);

        BlockStash bare = new BlockStash();
        bare.setTag("beta");
        section.put(ChunkUtil.indexBlock(3, 4, 6), bare);

        ExtraInfo info = new ExtraInfo();
        BlockRecordSection<BlockStash> restored = codec.decode(codec.encode(section, info), info);

        BlockStash restoredFilled = restored.get(ChunkUtil.indexBlock(3, 4, 5));
        BlockStash restoredBare = restored.get(ChunkUtil.indexBlock(3, 4, 6));
        assertNotSame(restoredFilled, restoredBare);
        assertEquals("alpha", restoredFilled.getTag());
        assertEquals("beta", restoredBare.getTag());
        assertNull(restoredBare.getPiles(), "the neighbour's pile must not bleed over");

        restoredFilled.ensurePile("main").itemsMutable().put("Ingredient_Carrot", 1);
        assertNull(restoredBare.getPiles(), "mutating one stash must not reach the other");
        assertTrue(restored.removeRecord(ChunkUtil.indexBlock(3, 4, 5)));
        assertEquals("beta", restored.get(ChunkUtil.indexBlock(3, 4, 6)).getTag(),
                "removing one stash must leave the other standing");
    }
}
