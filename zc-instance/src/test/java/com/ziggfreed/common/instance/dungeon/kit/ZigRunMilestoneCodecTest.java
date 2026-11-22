package com.ziggfreed.common.instance.dungeon.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.codec.CollectingExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The room kit's milestone effect ({@code ZigRunMilestone}): every flat leaf the trigger-volume inspector edits reads
 * as written beside the engine's own base keys (none left unread), {@code PlayerOnly} included, and is written back the
 * same, an unauthored effect writes none of its own, and its schema lists and documents every leaf and encodes, which
 * the Asset Editor needs of every Type. Tagged {@code engine-items}: the codec chains from the engine's effect base,
 * whose class init builds engine codecs that log.
 */
@Tag("engine-items")
class ZigRunMilestoneCodecTest {

    private static final String FULL = """
            { "Event": "BLOCK_USED", "Id": "Lock_Opened", "Placement": "Lock_A", "PlayerOnly": true }
            """;

    private static final List<String> LEAVES = List.of("Id", "Placement", "PlayerOnly");

    @Test
    void everyLeafReadsAndIsWrittenBack() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRunMilestoneEffect read = decode(FULL, info);
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        assertEquals(TriggerEventType.BLOCK_USED, read.getEventType(), "the native Event key reads beside them");

        BsonDocument written = ZigRunMilestoneEffect.codec().encode(read, new ExtraInfo());
        assertFull(written);
        assertFull(ZigRunMilestoneEffect.codec().encode(decode(written.toJson(), new CollectingExtraInfo()),
                new ExtraInfo()));
    }

    @Test
    void anUnauthoredEffectWritesNoneOfItsOwn() throws IOException {
        BsonDocument written = ZigRunMilestoneEffect.codec().encode(decode("{ \"Event\": \"ENTER\" }",
                new CollectingExtraInfo()), new ExtraInfo());
        for (String leaf : LEAVES) {
            assertFalse(written.containsKey(leaf), leaf + " stays unwritten, so it reads as its default");
        }
    }

    @Test
    void theSchemaListsEveryLeafAndEncodes() {
        ObjectSchema schema = ZigRunMilestoneEffect.codec().toSchema(new SchemaContext());
        for (String leaf : LEAVES) {
            assertNotNull(schema.getProperties().get(leaf), "the schema lists " + leaf);
            assertNotNull(schema.getProperties().get(leaf).getMarkdownDescription(), "and documents " + leaf);
        }
        assertNotNull(Schema.CODEC.encode(schema, new ExtraInfo()));
    }

    // ==================== the pieces ====================

    private static void assertFull(@Nonnull BsonDocument written) {
        assertEquals("Lock_Opened", written.getString("Id").getValue());
        assertEquals("Lock_A", written.getString("Placement").getValue());
        assertTrue(written.getBoolean("PlayerOnly").getValue(), "PlayerOnly reads and is written back as written");
    }

    @Nonnull
    private static ZigRunMilestoneEffect decode(@Nonnull String json, @Nonnull CollectingExtraInfo info)
            throws IOException {
        ZigRunMilestoneEffect decoded = ZigRunMilestoneEffect.codec().decodeJson(RawJsonReader.fromJsonString(json),
                info);
        assertNotNull(decoded, "the codec decodes " + json);
        return decoded;
    }
}
