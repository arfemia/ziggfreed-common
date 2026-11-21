package com.ziggfreed.common.instance.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.codec.CollectingExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.instance.dungeon.kit.ZigRewardChestEffect;

/**
 * The room kit's reward chest effect ({@code ZigRewardChest}): every flat leaf the trigger-volume inspector edits reads
 * as written beside the engine's own base keys (none left unread) and is written back the same, an unauthored effect
 * writes none of its own, a room placed turned turns the chest's spot as the engine turns a volume's offsets, and its
 * schema lists every leaf and encodes, which the Asset Editor needs of every Type. Tagged {@code engine-items}: the
 * codec chains from the engine's effect base, whose class init builds engine codecs that log.
 */
@Tag("engine-items")
class ZigRewardChestCodecTest {

    private static final String FULL = """
            { "Event": "BLOCK_USED", "Entry": 2, "Chest": "MyPack_Cache_Chest",
              "Offset": { "X": 2.5, "Y": 1, "Z": -3.5 },
              "OpenerLootable": "MyPack_Lock_Mining", "OpenerTitleKey": "mypack.lock.mining.title",
              "Placement": "Cache_A" }
            """;

    private static final List<String> LEAVES = List.of("Chest", "Offset", "OpenerLootable", "OpenerTitleKey",
            "Placement");

    @Test
    void everyLeafReadsAndIsWrittenBack() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRewardChestEffect read = decode(FULL, info);
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        assertEquals(TriggerEventType.BLOCK_USED, read.getEventType(), "the native Event key reads beside them");
        assertEquals(2, read.getEntry());

        BsonDocument written = ZigRewardChestEffect.codec().encode(read, new ExtraInfo());
        assertFull(written);
        assertFull(ZigRewardChestEffect.codec().encode(decode(written.toJson(), new CollectingExtraInfo()),
                new ExtraInfo()));
    }

    @Test
    void anUnauthoredEffectWritesNoneOfItsOwn() throws IOException {
        BsonDocument written = ZigRewardChestEffect.codec().encode(decode("{ \"Event\": \"ENTER\" }",
                new CollectingExtraInfo()), new ExtraInfo());
        for (String leaf : LEAVES) {
            assertFalse(written.containsKey(leaf), leaf + " stays unwritten, so it reads as its default");
        }
    }

    @Test
    void aTurnedRoomTurnsTheChestsSpot() throws IOException {
        ZigRewardChestEffect read = decode(FULL, new CollectingExtraInfo());
        read.rotateInPlace((float) (Math.PI / 2), new Vector3d(10.0, 64.0, -20.0));
        BsonDocument offset = ZigRewardChestEffect.codec().encode(read, new ExtraInfo()).getDocument("Offset");
        // One quarter turn takes (x, y, z) to (z, y, -x), as the engine turns every volume offset.
        assertEquals(-3.5, offset.getNumber("X").doubleValue(), 1e-9);
        assertEquals(1.0, offset.getNumber("Y").doubleValue(), 1e-9);
        assertEquals(-2.5, offset.getNumber("Z").doubleValue(), 1e-9);

        ZigRewardChestEffect bare = decode("{ \"Event\": \"ENTER\" }", new CollectingExtraInfo());
        bare.rotateInPlace((float) Math.PI, new Vector3d());
        assertFalse(ZigRewardChestEffect.codec().encode(bare, new ExtraInfo()).containsKey("Offset"),
                "an unauthored spot stays the volume's own Position");
    }

    @Test
    void theSchemaListsEveryLeafAndEncodes() {
        ObjectSchema schema = ZigRewardChestEffect.codec().toSchema(new SchemaContext());
        for (String leaf : LEAVES) {
            assertNotNull(schema.getProperties().get(leaf), "the schema lists " + leaf);
            assertNotNull(schema.getProperties().get(leaf).getMarkdownDescription(), "and documents " + leaf);
        }
        assertNotNull(Schema.CODEC.encode(schema, new ExtraInfo()));
    }

    // ==================== the pieces ====================

    private static void assertFull(@Nonnull BsonDocument written) {
        assertEquals("MyPack_Cache_Chest", written.getString("Chest").getValue());
        assertEquals("MyPack_Lock_Mining", written.getString("OpenerLootable").getValue());
        assertEquals("mypack.lock.mining.title", written.getString("OpenerTitleKey").getValue());
        assertEquals("Cache_A", written.getString("Placement").getValue());
        BsonDocument offset = written.getDocument("Offset");
        assertEquals(2.5, offset.getNumber("X").doubleValue(), 1e-9);
        assertEquals(1.0, offset.getNumber("Y").doubleValue(), 1e-9);
        assertEquals(-3.5, offset.getNumber("Z").doubleValue(), 1e-9);
    }

    @Nonnull
    private static ZigRewardChestEffect decode(@Nonnull String json, @Nonnull CollectingExtraInfo info)
            throws IOException {
        ZigRewardChestEffect decoded = ZigRewardChestEffect.codec().decodeJson(RawJsonReader.fromJsonString(json),
                info);
        assertNotNull(decoded, "the codec decodes " + json);
        return decoded;
    }
}
