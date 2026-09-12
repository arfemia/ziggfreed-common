package com.ziggfreed.common.progress.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestAxisRow;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.quest.asset.QuestPool;

/**
 * The ONE axis vocabulary on the runtime: a consumer registers the lists a generated family may
 * fan out over through its registrar, attributed to it, and the shared quest store resolves with
 * exactly that instance.
 */
class ProgressionAxesTest {

    private static final String CONSUMER = "yourmod";
    private static final String SOURCE = "yourmod:ores";

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        QuestAssetStore.getInstance().mergeQuests(Map.of());
        QuestAssetStore.getInstance().mergeGenerators(Map.of());
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
        QuestAssetStore.getInstance().mergeQuests(Map.of());
        QuestAssetStore.getInstance().mergeGenerators(Map.of());
    }

    @Test
    void anAxisRegisteredThroughTheRegistrarLandsInTheOneVocabularyAttributedToItsOwner() {
        ProgressionRuntime.registrar(CONSUMER).questAxis(SOURCE,
                filter -> List.of(QuestAxisRow.of("copper"), QuestAxisRow.of("iron")));

        assertTrue(ProgressionRuntime.questAxes().isRegistered(SOURCE));
        assertEquals(CONSUMER, ProgressionRuntime.questAxes().info().get(SOURCE).owner());
        assertSame(ProgressionRuntime.questAxes(), ProgressionRuntime.questAxes(),
                "one instance, handed to every store that walks axes");
    }

    @Test
    void theStoreResolvedWithTheRuntimesAxesExpandsAFamilyOverTheRegisteredList() throws Exception {
        ProgressionRuntime.registrar(CONSUMER).questAxis(SOURCE,
                filter -> List.of(QuestAxisRow.of("copper"), QuestAxisRow.of("iron")));
        QuestAssetStore store = QuestAssetStore.getInstance();
        store.mergeQuests(Map.of("gather_base", decodeQuest("""
                { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore", "Amount": 10 } } }
                """, "gather_base")));
        store.mergeGenerators(Map.of("ladder", decodeGenerator("""
                { "Base": "gather_base", "IdPattern": "gather_{ore}",
                  "ForEach": [ { "Token": "ore", "Source": "yourmod:ores" } ],
                  "Child": { "Objectives": { "collect": { "Target": "{ore}_Ore" } } } }
                """, "ladder")));

        QuestPool pool = store.resolveAll(ProgressionRuntime.questAxes());

        assertNotNull(pool.definition("gather_copper"));
        assertNotNull(pool.definition("gather_iron"));
        assertEquals("iron_Ore", pool.definition("gather_iron").quest().objective("collect").target());
    }

    @Test
    void aResetForgetsEveryRegisteredAxis() {
        ProgressionRuntime.registrar(CONSUMER).questAxis(SOURCE, filter -> List.of());
        ProgressionRuntime.resetForTests();

        assertFalse(ProgressionRuntime.questAxes().isRegistered(SOURCE));
    }

    private static QuestAsset decodeQuest(String json, String id) throws Exception {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, null);
        return QuestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }

    private static QuestGeneratorAsset decodeGenerator(String json, String id) throws Exception {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestGeneratorAsset.class, id, null);
        return QuestGeneratorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(data));
    }
}
