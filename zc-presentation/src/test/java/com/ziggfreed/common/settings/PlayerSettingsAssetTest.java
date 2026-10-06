package com.ziggfreed.common.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The owner's record over every player's own choices: unauthored it shows the tracker, fixes nothing and
 * updates at every step; the shipped file says the same; an owner entry restates only the leaves it names;
 * the owner file wins over the pack's record; and the exported schema offers the four words and declares
 * every default the editor shows.
 */
class PlayerSettingsAssetTest {

    @TempDir
    Path dir;

    static PlayerSettingsAsset record(@Nonnull String json, @Nonnull String id, @Nullable PlayerSettingsAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(PlayerSettingsAsset.class, id,
                parent == null ? null : parent.getId());
        return PlayerSettingsAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of());
        PlayerSettingsConfig.getInstance().mergeOwnerLayer(Map.of());
        PlayerSettingsOwnerLayers.setDirectory(PlayerSettingsOwnerLayers.DEFAULT_DIRECTORY);
    }

    @Test
    void anUnauthoredRecordShowsTheTrackerFixesNothingAndUpdatesEveryStep() throws Exception {
        PlayerSettingsAsset bare = record("{ }", "Default", null);

        assertTrue(bare.questTracker().showDefault());
        assertFalse(bare.questTracker().showLocked());
        assertFalse(bare.questTracker().spotLocked());
        assertEquals(NotificationLevel.EVERY_UPDATE, bare.notifications().level().defaultLevel());
        assertFalse(bare.notifications().level().locked());

        assertSame(SurfaceRules.NONE, PlayerSettingsConfig.getInstance().questTracker(),
                "before anything loads the fold answers the same: nothing said");
        assertEquals(NotificationLevel.EVERY_UPDATE, PlayerSettingsConfig.getInstance().level().defaultLevel());
    }

    @Test
    void theShippedRecordSaysTheSame() throws Exception {
        String json;
        try (InputStream in = PlayerSettingsAssetTest.class
                .getResourceAsStream("/Server/ZiggfreedCommon/PlayerSettings/Default.json")) {
            assertNotNull(in, "the library ships Server/ZiggfreedCommon/PlayerSettings/Default.json");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        PlayerSettingsAsset shipped = record(json, "Default", null);

        assertTrue(shipped.questTracker().showDefault());
        assertFalse(shipped.questTracker().showLocked());
        assertFalse(shipped.questTracker().spotLocked());
        assertEquals(NotificationLevel.EVERY_UPDATE, shipped.notifications().level().defaultLevel());
        assertFalse(shipped.notifications().level().locked());
    }

    @Test
    void anOwnerEntryRestatesOnlyTheLeavesItNames() throws Exception {
        PlayerSettingsAsset pack = record("{ \"QuestTracker\": { \"Show\": { \"Default\": false } },"
                + " \"Notifications\": { \"Level\": { \"Default\": \"Milestones\" } } }", "Default", null);
        PlayerSettingsAsset owner = record("{ \"QuestTracker\": { \"Show\": { \"Locked\": true } },"
                + " \"Notifications\": { \"Level\": { \"Locked\": true } } }", "Default", pack);

        assertFalse(owner.questTracker().showDefault(), "the pack's default stands");
        assertTrue(owner.questTracker().showLocked());
        assertFalse(owner.questTracker().spotLocked(), "a group nobody restated is still unlocked");
        assertEquals(NotificationLevel.MILESTONES, owner.notifications().level().defaultLevel());
        assertTrue(owner.notifications().level().locked());
    }

    @Test
    void theOwnerFileWinsOverThePackRecord() throws Exception {
        PlayerSettingsConfig.getInstance().mergePackLayer(Map.of("Default",
                record("{ \"Notifications\": { \"Level\": { \"Default\": \"Finishes\" } } }", "Default", null)));
        Files.writeString(dir.resolve(PlayerSettingsOwnerLayers.FILE),
                "{ \"Default\": { \"Notifications\": { \"Level\": { \"Locked\": true } } } }");
        PlayerSettingsOwnerLayers.setDirectory(dir);

        PlayerSettingsOwnerLayers.reload();

        assertEquals(NotificationLevel.FINISHES, PlayerSettingsConfig.getInstance().level().defaultLevel(),
                "the owner entry decodes over the pack's record");
        assertTrue(PlayerSettingsConfig.getInstance().level().locked());
    }

    @Test
    void aLevelNobodyKnowsReadsAsTheDefault() throws Exception {
        PlayerSettingsAsset typo = record("{ \"Notifications\": { \"Level\": { \"Default\": \"Loud\" } } }",
                "Default", null);

        assertEquals(NotificationLevel.DEFAULT, typo.notifications().level().defaultLevel());
    }

    @Test
    void theEditorSchemaOffersTheFourWordsAndDeclaresTheDefaults() {
        SchemaContext context = new SchemaContext();
        ObjectSchema root = PlayerSettingsAsset.CODEC.toSchema(context);

        ObjectSchema level = objectOf(objectOf(root.getProperties().get("Notifications"), context)
                .getProperties().get("Level"), context);
        StringSchema word = (StringSchema) level.getProperties().get("Default");
        assertArrayEquals(NotificationLevel.ids(), word.getEnum(), "a closed dropdown of the four words");
        assertEquals("EveryUpdate", word.getDefault());
        assertEquals(Boolean.FALSE, ((BooleanSchema) level.getProperties().get("Locked")).getDefault());

        ObjectSchema show = objectOf(objectOf(root.getProperties().get("QuestTracker"), context)
                .getProperties().get("Show"), context);
        assertEquals(Boolean.TRUE, ((BooleanSchema) show.getProperties().get("Default")).getDefault(),
                "unauthored, the tracker shows, and the editor must not show an unchecked box");
        assertEquals(Boolean.FALSE, ((BooleanSchema) show.getProperties().get("Locked")).getDefault());
    }

    /** The object a leaf describes: itself, or the definition it references (unwrapping a nullable union). */
    @Nonnull
    static ObjectSchema objectOf(@Nonnull Schema leaf, @Nonnull SchemaContext context) {
        Schema candidate = leaf.getAnyOf() == null ? leaf : leaf.getAnyOf()[0];
        if (candidate instanceof ObjectSchema object) {
            return object;
        }
        String ref = candidate.getRef();
        assertNotNull(ref, "the leaf is neither an object nor a reference");
        for (Map.Entry<String, Schema> entry : context.getDefinitions().entrySet()) {
            if (ref.endsWith(entry.getKey()) && entry.getValue() instanceof ObjectSchema object) {
                return object;
            }
        }
        throw new AssertionError("no definition behind " + ref + " in " + context.getDefinitions().keySet());
    }
}
