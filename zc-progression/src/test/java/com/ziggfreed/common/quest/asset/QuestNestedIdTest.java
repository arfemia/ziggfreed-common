package com.ziggfreed.common.quest.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A quest's id when its file sits in a {@code _}-marked folder, and what the store says when two
 * files land on one id.
 *
 * <p>The failure this guards is silent by construction: the engine keys an asset by its FILENAME
 * alone, so a second file that resolves to the same id simply replaces the first and the loser never
 * appears anywhere - no exception, no log line, just a quest nobody can take.
 */
class QuestNestedIdTest {

    /** Decode a quest as the asset store would, from a file at {@code path}. */
    private static QuestAsset decodeAt(String json, String filenameId, String path) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, filenameId, null);
        return QuestAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(Path.of(path), data));
    }

    /** The event layer the store is handed: keyed by the engine's filename-derived key. */
    private static Map<String, QuestAsset> layerOf(QuestAsset... assets) {
        Map<String, QuestAsset> layer = new LinkedHashMap<>();
        int i = 0;
        for (QuestAsset asset : assets) {
            layer.put("file" + i++, asset);
        }
        return layer;
    }

    @AfterEach
    void clearTheStore() {
        // The store is a process-wide singleton; leave it clean for every other test.
        QuestAssetStore.getInstance().mergeQuests(Map.of());
        QuestAssetStore.getInstance().mergeGenerators(Map.of());
    }

    @Nested
    class TheId {

        @Test
        void aMarkedFolderBecomesPartOfTheQuestId() throws IOException {
            QuestAsset asset = decodeAt("{ }", "Trork_Trouble",
                    "packs/MyPack/Server/ZiggfreedCommon/Quests/Zones/_Wilds/Trork_Trouble.json");

            assertEquals("wilds_trork_trouble", asset.getId());
        }

        @Test
        void anUnmarkedFolderLeavesTheIdAlone() throws IOException {
            QuestAsset asset = decodeAt("{ }", "Trork_Trouble",
                    "packs/MyPack/Server/ZiggfreedCommon/Quests/Zones/Trork_Trouble.json");

            assertEquals("trork_trouble", asset.getId());
        }

        @Test
        void theSourcePathIsRememberedForAFindingThatHasToNameIt() throws IOException {
            QuestAsset asset = decodeAt("{ }", "Trork_Trouble",
                    "packs/MyPack/Server/ZiggfreedCommon/Quests/_Wilds/Trork_Trouble.json");

            assertNotNull(asset.getSourcePath());
            assertTrue(asset.getSourcePath().contains("Trork_Trouble.json"), asset.getSourcePath());
        }

        @Test
        void aGeneratedQuestHasNoFileAndKeepsItsGeneratedId() throws IOException {
            // Decoded from a string with no path behind it, exactly as the generator expander does.
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, "gen_quest", null);
            QuestAsset asset = QuestAsset.CODEC.decodeJsonAsset(
                    RawJsonReader.fromJsonString("{ }"), new AssetExtraInfo<>(data));

            assertEquals("gen_quest", asset.getId());
            assertNull(asset.getSourcePath());
        }

        @Test
        void theStoreFilesAQuestUnderItsPrefixedIdNotTheEventKey() throws IOException {
            QuestAssetStore store = QuestAssetStore.getInstance();
            store.mergeQuests(layerOf(decodeAt("{ }", "Trork_Trouble",
                    "Server/ZiggfreedCommon/Quests/_Wilds/Trork_Trouble.json")));

            assertTrue(store.assets().containsKey("wilds_trork_trouble"),
                    "the folder is part of the id, so it must be part of the key too: "
                            + store.assets().keySet());
        }
    }

    @Nested
    class Collisions {

        @Test
        void twoFilesResolvingToOneIdAreReportedNamingBoth() throws IOException {
            QuestAssetStore store = QuestAssetStore.getInstance();
            // Different files, one effective id: _Wilds/Trork_Trouble and Wilds_Trork_Trouble.
            store.mergeQuests(layerOf(
                    decodeAt("{ }", "Trork_Trouble",
                            "Server/ZiggfreedCommon/Quests/_Wilds/Trork_Trouble.json"),
                    decodeAt("{ }", "Wilds_Trork_Trouble",
                            "Server/ZiggfreedCommon/Quests/Wilds_Trork_Trouble.json")));

            List<Finding> issues = store.resolve(null).issues();
            Finding duplicate = issues.stream()
                    .filter(f -> "DUPLICATE_QUEST_ID".equals(f.code()))
                    .findFirst().orElseThrow(() -> new AssertionError("expected a duplicate finding: " + issues));

            assertEquals(Severity.ERROR, duplicate.severity(), "the loser never appears at all");
            assertTrue(duplicate.message().contains("_Wilds"), duplicate.message());
            assertTrue(duplicate.message().contains("Wilds_Trork_Trouble.json"), duplicate.message());
        }

        @Test
        void distinctIdsAreNotReported() throws IOException {
            QuestAssetStore store = QuestAssetStore.getInstance();
            store.mergeQuests(layerOf(
                    decodeAt("{ }", "Trork_Trouble",
                            "Server/ZiggfreedCommon/Quests/_Wilds/Trork_Trouble.json"),
                    decodeAt("{ }", "Trork_Trouble",
                            "Server/ZiggfreedCommon/Quests/_Ashlands/Trork_Trouble.json")));

            assertEquals(2, store.assets().size(), "two folders, two ids, both survive");
            assertTrue(store.resolve(null).issues().stream()
                    .noneMatch(f -> "DUPLICATE_QUEST_ID".equals(f.code())));
        }

        @Test
        void aReservedDelimiterIsCheckedOnThePrefixedId() throws IOException {
            QuestAssetStore store = QuestAssetStore.getInstance();
            // The marked folder is what carries the reserved character, so a check against the bare
            // filename would never see it.
            store.mergeQuests(layerOf(decodeAt("{ \"Objectives\": { \"step\": { \"Kind\": \"talk\" } } }",
                    "Trork_Trouble", "Server/ZiggfreedCommon/Quests/_Wilds=North/Trork_Trouble.json")));

            QuestPool pool = store.resolve(null).pool();
            List<Finding> issues = QuestPoolValidator.validate(pool, null, null,
                    new InMemoryQuestProgressStore(), null);

            assertTrue(issues.stream().anyMatch(f -> "RESERVED_ID".equals(f.code())),
                    "an id a save cannot store must be reported: " + issues);
        }
    }
}
