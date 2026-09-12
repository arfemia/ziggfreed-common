package com.ziggfreed.common.objectives.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.objectives.producer.EncounterQuestAxes;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestAxisRow;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.quest.asset.QuestOwnerLayers;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.validation.Finding;

/**
 * The library's ONE quest publish, as a consumer relies on it: it rides the runtime's build so
 * the catalogue is in the engines the instant they exist whoever built them, it resolves with the
 * runtime's own axis vocabulary, it folds the contributed layer and the owner folder, a republish
 * request is harmless before the first publish and effective after it, and what it folded stays
 * readable for an audit.
 */
class ProgressionDefaultsPublishTest {

    private static final String CONSUMER = "yourmod";

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        resetEverything();
        QuestOwnerLayers.setDirectory(dir);
        ProgressionDefaults.register();
    }

    @AfterEach
    void tearDown() {
        QuestOwnerLayers.setDirectory(QuestOwnerLayers.DEFAULT_DIRECTORY);
        resetEverything();
    }

    private static void resetEverything() {
        ProgressionRuntime.resetForTests();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        QuestAssetStore store = QuestAssetStore.getInstance();
        store.mergeQuests(Map.of());
        store.mergeGenerators(Map.of());
        store.mergeContributed(Map.of());
    }

    private static QuestAsset quest(String id) throws Exception {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, null);
        return QuestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(
                "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\", \"Amount\": 10 } } }"),
                null, new AssetExtraInfo<>(data));
    }

    private static QuestGeneratorAsset generator(String json, String id) throws Exception {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestGeneratorAsset.class, id, null);
        return QuestGeneratorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(data));
    }

    private Path ownerFile(String fileName, String json) throws Exception {
        Path folder = Files.createDirectories(dir.resolve(QuestOwnerLayers.FOLDER));
        return Files.writeString(folder.resolve(fileName), json, StandardCharsets.UTF_8);
    }

    @Test
    void theLibrarySeedsItsOwnEncountersAxisIntoTheOneVocabulary() {
        assertTrue(ProgressionRuntime.questAxes().isRegistered(EncounterQuestAxes.SOURCE_ENCOUNTERS),
                "a family generated over the bosses needs no consumer to carry the list across");
        assertEquals(ProgressionDefaults.OWNER,
                ProgressionRuntime.questAxes().info().get(EncounterQuestAxes.SOURCE_ENCOUNTERS).owner());
    }

    // ==================== the boot publish ====================

    @Test
    void theBootPublishRidesTheBuildSoAnEngineReadFromAnywhereSeesTheCatalogue() throws Exception {
        QuestAssetStore.getInstance().mergeQuests(Map.of("shipped", quest("shipped")));
        assertFalse(ProgressionRuntime.isBuilt());
        assertEquals(QuestPool.EMPTY, ProgressionDefaults.questPool(), "nothing is published before the build");

        // Not the player-ready pass: a bare engine read, the way a ticking system's first tick or
        // a command builds the runtime on a server where that happens before any player is ready.
        assertNotNull(ProgressionRuntime.quests().quest("shipped"),
                "the catalogue is in the engine the instant it exists, whoever built it");
        assertNotNull(ProgressionDefaults.questPool().definition("shipped"),
                "and the pool accessor answers with the same fold");
    }

    @Test
    void theBootPublishHappensOnceAndEveryLaterChangeIsARepublish() throws Exception {
        QuestAssetStore store = QuestAssetStore.getInstance();
        store.mergeQuests(Map.of("shipped", quest("shipped")));
        ProgressionRuntime.quests();

        store.mergeQuests(Map.of("shipped", quest("shipped"), "later", quest("later")));
        assertNull(ProgressionRuntime.quests().quest("later"), "a merge publishes nothing by itself");
        ProgressionRuntime.ensureBuilt();
        assertNull(ProgressionRuntime.quests().quest("later"), "and a second build request is not a second boot publish");

        ProgressionDefaults.republishAssetContent();
        assertNotNull(ProgressionRuntime.quests().quest("later"), "a republish after the boot publish re-resolves the store");
    }

    @Test
    void anExplicitPublishBeforeAnyBuildIsTheBootPublishAndTheBuildItForcesDoesNotRepeatIt() throws Exception {
        QuestAssetStore.getInstance().mergeQuests(Map.of("shipped", quest("shipped")));

        ProgressionDefaults.publishAssetContent();

        assertTrue(ProgressionRuntime.isBuilt(), "the audit inside the publish reads the engines, which builds them");
        assertNotNull(ProgressionRuntime.quests().quest("shipped"));
        assertEquals(1, ProgressionRuntime.quests().quests().size());
    }

    // ==================== what the publish folds ====================

    @Test
    void thePublishResolvesGeneratorsWithTheAxesAConsumerRegistered() throws Exception {
        ProgressionRuntime.registrar(CONSUMER).questAxis("yourmod:ores",
                filter -> List.of(QuestAxisRow.of("copper"), QuestAxisRow.of("iron")));
        QuestAssetStore store = QuestAssetStore.getInstance();
        store.mergeQuests(Map.of("gather_base", quest("gather_base")));
        store.mergeGenerators(Map.of("ladder", generator("""
                { "Base": "gather_base", "IdPattern": "gather_{ore}",
                  "ForEach": [ { "Token": "ore", "Source": "yourmod:ores" } ],
                  "Child": { "Objectives": { "collect": { "Target": "{ore}_Ore" } } } }
                """, "ladder")));

        ProgressionDefaults.publishAssetContent();

        assertNotNull(ProgressionRuntime.quests().quest("gather_copper"),
                "the generated family reaches the engine from the library's publish alone");
        assertNotNull(ProgressionRuntime.quests().quest("gather_iron"));
        assertNotNull(ProgressionDefaults.questPool().definition("gather_copper"),
                "and the pool accessor answers with the same fold");
    }

    @Test
    void aRepublishRequestIsNothingBeforeTheFirstPublishAndRepublishesAfterIt() throws Exception {
        QuestAssetStore store = QuestAssetStore.getInstance();
        store.mergeContributed(Map.of("early", quest("early")));

        ProgressionDefaults.republishAssetContent();
        assertFalse(ProgressionRuntime.isBuilt(),
                "before the boot publish a request does nothing, and above all does not build the runtime");
        assertEquals(QuestPool.EMPTY, ProgressionDefaults.questPool());

        ProgressionDefaults.publishAssetContent();
        assertNotNull(ProgressionRuntime.quests().quest("early"), "the boot publish folds what was contributed by then");

        store.mergeContributed(Map.of("early", quest("early"), "late", quest("late")));
        assertNull(ProgressionRuntime.quests().quest("late"), "contributing publishes nothing by itself");

        ProgressionDefaults.republishAssetContent();
        assertNotNull(ProgressionRuntime.quests().quest("late"), "a republish after the first publish re-resolves the store");
        assertNotNull(ProgressionDefaults.questPool().definition("late"));
    }

    @Test
    void whatTheFoldReportedStaysReadableForAnAudit() throws Exception {
        QuestAssetStore store = QuestAssetStore.getInstance();
        // Two entries whose bodies both carry the id 'twice': the id is what files an entry.
        store.mergeContributed(Map.of("a", quest("twice"), "b", quest("twice")));

        ProgressionDefaults.publishAssetContent();

        List<String> codes = ProgressionDefaults.questLoadFindings().stream().map(Finding::code).toList();
        assertTrue(codes.contains("DUPLICATE_QUEST_ID"), "the fold's own findings survive the publish: " + codes);
        assertNotNull(ProgressionDefaults.questPool().definition("twice"));
    }

    // ==================== the owner folder ====================

    @Test
    void anOwnerFolderQuestReachesTheEngineThePoolAndTheAuditLikeAnyOther() throws Exception {
        QuestAssetStore.getInstance().mergeQuests(Map.of("shipped", quest("shipped")));
        ownerFile("Shipped.json", "{ \"Objectives\": { \"collect\": { \"Amount\": 25 } } }");
        ownerFile("Town_Errand.json", "{ \"Parent\": \"shipped\", \"Repeat\": { \"ResetsOnComplete\": [\"nobody\"] } }");

        ProgressionDefaults.publishAssetContent();

        assertEquals(25L, ProgressionRuntime.quests().quest("shipped").objective("collect").amount(),
                "a same-id owner file retunes the shipped quest in the engine");
        assertNotNull(ProgressionRuntime.quests().quest("town_errand"), "an owner quest of its own reaches the engine");
        assertNotNull(ProgressionDefaults.questPool().definition("town_errand"), "and the pool");
        List<Finding> audit = QuestPoolValidator.validate(ProgressionDefaults.questPool(),
                ProgressionRuntime.quests(), ProgressionRuntime.gateKinds());
        assertTrue(audit.stream().anyMatch(f -> "UNKNOWN_RESET_TARGET".equals(f.code())
                        && "town_errand".equals(f.sourceId())),
                "and the pool validator audits it like any other quest: " + audit);
    }

    @Test
    void theOwnerFolderIsReReadByEveryPublish() throws Exception {
        ProgressionDefaults.publishAssetContent();
        assertNull(ProgressionRuntime.quests().quest("late"));

        ownerFile("Late.json", "{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }");
        ProgressionDefaults.republishAssetContent();

        assertNotNull(ProgressionRuntime.quests().quest("late"),
                "a file dropped into the owner folder reaches the engine on the next republish");
    }

    @Test
    void aMalformedOwnerFileIsReportedAndCostsOnlyItself() throws Exception {
        QuestAssetStore.getInstance().mergeQuests(Map.of("shipped", quest("shipped")));
        ownerFile("Broken.json", "{ not json");

        ProgressionDefaults.publishAssetContent();

        assertNotNull(ProgressionRuntime.quests().quest("shipped"), "the boot is untouched");
        List<String> codes = ProgressionDefaults.questLoadFindings().stream().map(Finding::code).toList();
        assertTrue(codes.contains("OWNER_FILE_UNREADABLE"), "and the file is reported for the audit: " + codes);
    }
}
