package com.ziggfreed.common.objectives.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.builtin.adventure.objectives.config.completion.ObjectiveCompletionAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.ObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.objectives.task.ObjectiveTask;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.CollectingExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * Each Type the library registers into Hytale's own Objectives decodes its keys beside the engine base's (none left
 * unread), reads an unauthored group as none, decodes under its {@code Type} through the engine's own union, and
 * exports a schema the Asset Editor can take, the union's included. Tagged {@code engine-items}: the task base's
 * class init builds the engine's task conditions, whose codecs reach the engine's items, which log at class init;
 * and a task's schema names the engine's Item store, so it exports over a swapped empty one.
 */
@Tag("engine-items")
class NativeObjectiveTypesCodecTest {

    @Test
    void theMomentTaskReadsItsGroupAndCountsByCount() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigMomentTaskAsset task = decode(ZigMomentTaskAsset.CODEC, "{\"Count\": 5, \"DescriptionId\": \"zigtest.step\", "
                + "\"Moment\": {\"Kind\": \"KILL_ENTITY\", \"Target\": \"Trork_Warrior\", \"MatchMode\": \"EXACT\", "
                + "\"Amount\": 99, \"TextKey\": \"zigtest.line\"}}", info);
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        assertEquals(5, task.getCount(), "the native Count reads beside the group");
        assertEquals("zigtest.step", task.getDescriptionId(), "and so does the native DescriptionId");
        assertNotNull(task.getMoment(), "the Moment group is read");
        assertEquals("zigtest.line", task.getMoment().getTextKey(), "in the shape every quest step has");
        ObjectiveDef step = task.def();
        assertNotNull(step, "a group naming a Kind is a step");
        assertEquals("KILL_ENTITY", step.kind());
        assertTrue(step.matches("trork_warrior", null), "matched as a quest step matches, ignoring case");
        assertEquals(5L, step.amount(), "Count is the step's number; the group's own Amount is not read");

        ZigMomentTaskAsset bare = decode(ZigMomentTaskAsset.CODEC, "{}", new CollectingExtraInfo());
        assertNull(bare.getMoment(), "an unauthored group is none");
        assertNull(bare.def(), "and no step, so the task never counts");
        assertEquals(1, bare.getCount(), "an unauthored Count is the engine's own default");
        try (EngineAssetStores.Swap items = EngineAssetStores.emptyItems()) {
            assertSchema(ZigMomentTaskAsset.CODEC, "Moment");
            Schema count = ZigMomentTaskAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Count");
            assertEquals(Integer.valueOf(1), assertInstanceOf(IntegerSchema.class, count).getDefault(),
                    "the Asset Editor is offered the Count an unwritten task reads as");
        }
    }

    @Test
    void theMomentTaskDecodesUnderItsTypeThroughTheEngineUnion() throws IOException {
        ObjectiveTaskAsset.CODEC.register(ZigMomentTaskAsset.TYPE, ZigMomentTaskAsset.class, ZigMomentTaskAsset.CODEC);
        CollectingExtraInfo info = new CollectingExtraInfo();
        ObjectiveTaskAsset task = ObjectiveTaskAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Type\": \"ZigMoment\", \"Count\": 3, \"Moment\": {\"Kind\": \"BREAK_BLOCK\"}}"), info);
        assertInstanceOf(ZigMomentTaskAsset.class, task, "the Type key picks the arm");
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        try (EngineAssetStores.Swap items = EngineAssetStores.emptyItems()) {
            assertEncodes(ObjectiveTaskAsset.CODEC.toSchema(new SchemaContext()));
        }
    }

    @Test
    void theRunningTaskKeepsItsCountThroughTheEngineSavePath() throws IOException {
        // The engine saves a live objective's tasks through the running-task union (Objective's CurrentTasks), so
        // the count a player has earned is what a save writes and a restart reads back.
        ObjectiveTaskAsset.CODEC.register(ZigMomentTaskAsset.TYPE, ZigMomentTaskAsset.class, ZigMomentTaskAsset.CODEC);
        ObjectiveTask.CODEC.register(ZigMomentTaskAsset.TYPE, ZigMomentTask.class, ZigMomentTask.CODEC);
        CollectingExtraInfo info = new CollectingExtraInfo();
        ObjectiveTask saved = ObjectiveTask.CODEC.decodeJson(RawJsonReader.fromJsonString("{\"Type\": \"ZigMoment\", "
                + "\"Task\": {\"Type\": \"ZigMoment\", \"Count\": 5, \"Moment\": {\"Kind\": \"KILL_ENTITY\", "
                + "\"Target\": \"Trork_Warrior\"}}, \"Count\": 3, \"TaskSetIndex\": 1, \"TaskIndex\": 2}"), info);
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        ZigMomentTask task = assertInstanceOf(ZigMomentTask.class, saved, "the Type key picks the running task");
        assertFalse(task.checkCompletion(), "3 of 5 is not yet done");

        BsonDocument encoded = ObjectiveTask.CODEC.encode(task, new ExtraInfo()).asDocument();
        assertEquals("ZigMoment", encoded.getString("Type").getValue(), "a save names the running task's Type");
        assertEquals(3, encoded.getInt32("Count").getValue(), "and writes the count earned");
        assertEquals(5, encoded.getDocument("Task").getInt32("Count").getValue(), "beside the step it counts toward");

        ZigMomentTask restarted = assertInstanceOf(ZigMomentTask.class,
                ObjectiveTask.CODEC.decode(encoded, new ExtraInfo()), "a restart reads the same task back");
        assertEquals(encoded, ObjectiveTask.CODEC.encode(restarted, new ExtraInfo()),
                "and saves it again unchanged, count included");
        assertEquals(5, restarted.getAsset().getCount());
        ObjectiveDef step = restarted.getAsset().def();
        assertNotNull(step, "its step survives the round trip");
        assertEquals("KILL_ENTITY", step.kind());
    }

    @Test
    void theRewardsCompletionReadsItsRowsAndTitle() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        ZigRewardsCompletionAsset completion = decode(ZigRewardsCompletionAsset.CODEC, "{\"Rewards\": [{\"Kind\": "
                + "\"Track\", \"Params\": {\"Track\": \"Zigtest_Mining\", \"Amount\": \"5\"}}], \"TitleKey\": "
                + "\"zigtest.claim\"}", info);
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        RewardEntryAsset[] rows = completion.getRewards();
        assertNotNull(rows, "the rows are read");
        assertEquals(1, rows.length);
        assertEquals("Track", rows[0].getKind(), "in the entry shape a quest pays in");
        assertEquals("zigtest.claim", completion.getTitleKey());
        assertNull(decode(ZigRewardsCompletionAsset.CODEC, "{}", new CollectingExtraInfo()).getRewards(),
                "unauthored rows are none");
        assertSchema(ZigRewardsCompletionAsset.CODEC, "Rewards");
        assertUnion(ZigRewardsCompletionAsset.TYPE, ZigRewardsCompletionAsset.class, ZigRewardsCompletionAsset.CODEC,
                "{\"Type\": \"ZigRewards\"}");
    }

    @Test
    void theTrackCompletionReadsItsTrackAndAmount() throws IOException {
        CollectingExtraInfo info = new CollectingExtraInfo();
        TrackCompletionAsset completion = decode(TrackCompletionAsset.CODEC,
                "{\"Track\": \" Zigtest_Mining \", \"Amount\": 500, \"TitleKey\": \"zigtest.claim\"}", info);
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        assertEquals("Zigtest_Mining", completion.trackId(), "the track id is trimmed");
        assertEquals(500L, completion.amount());
        assertEquals("zigtest.claim", completion.getTitleKey());
        TrackCompletionAsset bare = decode(TrackCompletionAsset.CODEC, "{}", new CollectingExtraInfo());
        assertNull(bare.trackId(), "an unauthored track is none");
        assertEquals(0L, bare.amount(), "and an unauthored amount pays nothing");
        assertSchema(TrackCompletionAsset.CODEC, "Track");
        assertUnion(TrackCompletionAsset.TYPE, TrackCompletionAsset.class, TrackCompletionAsset.CODEC,
                "{\"Type\": \"ZigTrack\", \"Track\": \"Zigtest_Mining\", \"Amount\": 1}");
    }

    // ==================== helpers ====================

    @Nonnull
    private static <T> T decode(@Nonnull BuilderCodec<T> codec, @Nonnull String json, @Nonnull ExtraInfo info)
            throws IOException {
        T decoded = codec.decodeJson(RawJsonReader.fromJsonString(json), info);
        assertNotNull(decoded, "the codec decodes " + json);
        return decoded;
    }

    /** The completion decodes under its Type through the engine's completion union, which still exports. */
    private static <T extends ObjectiveCompletionAsset> void assertUnion(@Nonnull String type,
            @Nonnull Class<T> assetClass, @Nonnull Codec<T> codec, @Nonnull String json) throws IOException {
        ObjectiveCompletionAsset.CODEC.register(type, assetClass, codec);
        CollectingExtraInfo info = new CollectingExtraInfo();
        ObjectiveCompletionAsset decoded = ObjectiveCompletionAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json),
                info);
        assertInstanceOf(assetClass, decoded, "the Type key picks the arm");
        assertTrue(info.getUnknownKeys().isEmpty(), () -> "keys left unread: " + info.getUnknownKeys());
        assertEncodes(ObjectiveCompletionAsset.CODEC.toSchema(new SchemaContext()));
    }

    /** The Type's schema documents its own key and encodes, which the Asset Editor needs of every Type. */
    private static void assertSchema(@Nonnull BuilderCodec<?> codec, @Nonnull String key) {
        ObjectSchema schema = codec.toSchema(new SchemaContext());
        assertNotNull(schema.getProperties().get(key), "the schema lists " + key);
        assertNotNull(schema.getProperties().get(key).getMarkdownDescription(), "and documents it");
        assertEncodes(schema);
    }

    private static void assertEncodes(@Nonnull Schema schema) {
        assertNotNull(Schema.CODEC.encode(schema, new ExtraInfo()));
    }
}
