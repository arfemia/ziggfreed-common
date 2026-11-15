package com.ziggfreed.common.rune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * {@link RuneSockets#CODEC}: what a player's rune lines hold survives the save the engine puts them through. Each
 * support comes back in its own socket of its own line, whole (its count and its metadata), a line holding nothing is
 * not written, and a record holding nothing writes no lines at all.
 *
 * <p>Tagged {@code engine-items}: a real {@link ItemStack} can only be built under the engine's own log manager, which
 * only the {@code engineItemTest} task starts its JVM with. The item store is swapped for an empty one, which is all
 * a stack's constructor needs.
 */
@Tag("engine-items")
class RuneSocketsCodecTest {

    private static final String FIRST_LINE = "test:slot_1";
    private static final String SECOND_LINE = "test:slot_2";
    private static final String EMPTIED_LINE = "test:slot_3";

    @Test
    void everySocketedSupportComesBackInItsOwnSocket() {
        try (EngineAssetStores.Swap items = EngineAssetStores.emptyItems()) {
            ItemStack marked = new ItemStack("Test_Rune_Alpha", 1,
                    new BsonDocument("Test_Mark", new BsonString("kept")));
            ItemStack pair = new ItemStack("Test_Rune_Beta", 2, null);
            RuneSockets record = new RuneSockets();
            record.put(FIRST_LINE, Arrays.asList(marked, null));
            record.put(SECOND_LINE, Arrays.asList(null, pair));
            record.put(EMPTIED_LINE, Arrays.asList(pair, null));
            record.put(EMPTIED_LINE, Arrays.asList(null, null));

            BsonDocument saved = RuneSockets.CODEC.encode(record, new ExtraInfo());
            assertEquals(new BsonInt32(RuneSockets.VERSION), saved.get("Version"));
            BsonDocument lines = saved.getDocument("Lines");
            assertEquals(Set.of(FIRST_LINE, SECOND_LINE), lines.keySet(), "a line holding nothing is not written");
            assertEquals(Set.of("1"), lines.getDocument(FIRST_LINE).keySet(), "a support is saved under its socket");
            assertEquals(Set.of("2"), lines.getDocument(SECOND_LINE).keySet());

            RuneSockets restored = RuneSockets.CODEC.decode(saved, new ExtraInfo());
            assertEquals(Set.of(FIRST_LINE, SECOND_LINE), restored.lineIds());
            List<ItemStack> first = restored.supports(FIRST_LINE);
            assertEquals(marked, first.get(0), "the support comes back whole, its metadata included");
            assertEquals("kept", first.get(0).getFromMetadataOrNull("Test_Mark", Codec.STRING));
            assertNull(first.get(1), "an empty socket stays empty");
            List<ItemStack> second = restored.supports(SECOND_LINE);
            assertNull(second.get(0));
            assertEquals(pair, second.get(1), "a support keeps its socket and its count");
            assertEquals(Arrays.asList(null, null), restored.supports(EMPTIED_LINE));
        }
    }

    /**
     * A support whose item the server no longer has (its mod removed) is read as written and saved again, never
     * dropped: the engine's item codec checks the id only through a late validator, which a decode never runs, and
     * such a stack answers the engine's unknown item, which is no core. The item store here is empty, so the id is
     * unknown exactly as it is on a live server whose item store lacks it.
     */
    @Test
    void aSupportWhoseItemIsGoneIsKept() {
        try (EngineAssetStores.Swap items = EngineAssetStores.emptyItems()) {
            BsonDocument gone = new BsonDocument("Id", new BsonString("Gone_Mod_Rune"))
                    .append("Quantity", new BsonInt32(1));
            BsonDocument saved = new BsonDocument("Version", new BsonInt32(RuneSockets.VERSION))
                    .append("Lines", new BsonDocument(FIRST_LINE, new BsonDocument("1", gone)));

            RuneSockets restored = RuneSockets.CODEC.decode(saved, new ExtraInfo());
            ItemStack kept = restored.supports(FIRST_LINE).get(0);
            assertEquals("Gone_Mod_Rune", kept.getItemId(), "an item the server does not have is read as written");
            assertSame(Item.UNKNOWN, kept.getItem(), "and answers the engine's unknown item");
            assertFalse(restored.put(FIRST_LINE, restored.supports(FIRST_LINE)),
                    "the window's write-back keeps it where it was");
            assertEquals("Gone_Mod_Rune", RuneSockets.CODEC.encode(restored, new ExtraInfo()).getDocument("Lines")
                    .getDocument(FIRST_LINE).getDocument("1").getString("Id").getValue(), "and the next save writes it");
        }
    }

    @Test
    void aRecordHoldingNothingWritesNoLines() {
        BsonDocument saved = RuneSockets.CODEC.encode(new RuneSockets(), new ExtraInfo());
        assertFalse(saved.containsKey("Lines"), "nothing socketed writes nothing");
        RuneSockets restored = RuneSockets.CODEC.decode(saved, new ExtraInfo());
        assertTrue(restored.isEmpty());
    }
}
