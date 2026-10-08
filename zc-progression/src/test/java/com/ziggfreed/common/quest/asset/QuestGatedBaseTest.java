package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.codes;
import static com.ziggfreed.common.quest.asset.QuestAssetStoreContributionTest.quest;
import static com.ziggfreed.common.quest.asset.QuestGeneratorTest.generator;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.AssetMergeAdapter;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * A generated family follows its base through the mod gate: a base the gate refused writes nothing and
 * reports nothing, a base nobody authored still reports UNKNOWN_BASE, and a child whose own body names
 * an absent mod is dropped like a file would be.
 */
class QuestGatedBaseTest {

    private static final QuestAssetStore STORE = QuestAssetStore.getInstance();

    private static final String MMO = "Ziggfreed:MMOSkillTree";

    @BeforeEach
    @AfterEach
    void emptyStore() {
        STORE.mergeQuests(Map.of());
        STORE.mergeGenerators(Map.of());
        STORE.mergeContributed(Map.of());
        ModGates.useProbeForTests(null);
    }

    private static String ladderOver(String base, String child) {
        return "{ \"Base\": \"" + base + "\", \"IdPattern\": \"" + base + "_{material}\","
                + " \"ForEach\": [ { \"Token\": \"material\", \"Values\": [\"copper\"] } ],"
                + " \"Child\": " + child + " }";
    }

    @Test
    void aFamilyWhoseBaseTheGateRefusedWritesNothingAndSaysNothing() throws Exception {
        STORE.mergeQuests(Map.of(), Set.of("mmo_gather_base"));
        STORE.mergeGenerators(Map.of("mmo_ladder", generator(ladderOver("mmo_gather_base",
                "{ \"Objectives\": { \"collect\": { \"Target\": \"{material}_Ore\" } } }"), "mmo_ladder")));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("mmo_gather_base_copper"));
        assertFalse(codes(resolution.issues()).contains("UNKNOWN_BASE"), "the base is absent on purpose");
    }

    @Test
    void aBaseNobodyAuthoredIsStillReported() throws Exception {
        STORE.mergeQuests(Map.of(), Set.of("mmo_gather_base"));
        STORE.mergeGenerators(Map.of("typo_ladder", generator(ladderOver("gather_bsae",
                "{ \"Objectives\": { \"collect\": { \"Target\": \"{material}_Ore\" } } }"), "typo_ladder")));

        assertTrue(codes(STORE.resolve(null).issues()).contains("UNKNOWN_BASE"));
    }

    @Test
    void aChildWhoseOwnBodyNamesAnAbsentModIsDropped() throws Exception {
        ModGates.useProbeForTests(param -> param != null && param.trim().equals("Ziggfreed:MMOSkillTree") ? 0.0 : 1.0);
        STORE.mergeQuests(Map.of("gather_base", quest("gather_base", 5)));
        STORE.mergeGenerators(Map.of("ladder", generator(ladderOver("gather_base",
                "{ \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
                        + " \"Param\": \"Ziggfreed:MMOSkillTree\", \"Min\": 1 } ] } }"), "ladder")));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("gather_base_copper"), "a generated file obeys the file rule");
        assertNotNull(resolution.pool().definition("gather_base"), "its base, ungated, stays");
    }

    // ==================== a base under a _-marked folder ====================

    /** The engine's quest map with its pack-loading door opened, as the load event hands it over. */
    static final class PackMap extends DefaultAssetMap<String, QuestAsset> {

        /**
         * Load one quest FILE at {@code path}: the engine keys it by its filename alone, while the
         * codec folds every {@code _}-marked folder above it into the quest's own id.
         */
        PackMap load(String filenameId, String path, String json) throws IOException {
            QuestAsset asset = QuestAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                    new AssetExtraInfo<>(Path.of(path), new AssetExtraInfo.Data(QuestAsset.class, filenameId, null)));
            putAll("Ziggfreed:SeasonsOfOrbis", QuestAsset.CODEC, Map.of(filenameId, asset),
                    Map.of(filenameId, Path.of(path)), Map.of(filenameId, Set.of()));
            return this;
        }
    }

    /** Fold the quest map exactly as the registrar's Quests load handler does. */
    static void foldAsTheLoadHandlerDoes(DefaultAssetMap<String, QuestAsset> files) {
        STORE.mergeQuests(AssetMergeAdapter.gate("Quests", files, q -> GateSpec.missingMod(q.getRequires())));
    }

    /**
     * The engine keys a file under {@code _Harvest_Feast/} by its bare filename, but the quest's id, the
     * store's key and so the id a generator's {@code Base} names all carry the folder. The refused id
     * has to land in that same space, or the family over a gated base reports its base as missing.
     */
    @Test
    void aGatedBaseUnderAMarkedFolderIsRefusedUnderTheFoldedIdItsGeneratorNames() throws Exception {
        PackMap files = new PackMap().load("Mmo_Gather_Base",
                "Server/ZiggfreedCommon/Quests/SeasonsOfOrbis/_Harvest_Feast/Mmo_Gather_Base.json",
                "{ \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\", \"Param\": \"" + MMO + "\","
                        + " \"Min\": 1 } ] }, \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\","
                        + " \"Target\": \"Ore\", \"Amount\": 5 } } }");
        STORE.mergeGenerators(Map.of("harvest_ladder", generator(ladderOver("Harvest_Feast_Mmo_Gather_Base",
                "{ \"Objectives\": { \"collect\": { \"Target\": \"{material}_Ore\" } } }"), "harvest_ladder")));

        ModGates.useProbeForTests(param -> param != null && param.trim().equals(MMO) ? 0.0 : 1.0);
        foldAsTheLoadHandlerDoes(files);
        QuestAssetStore.Resolution without = STORE.resolve(null);

        assertNull(without.pool().definition("harvest_feast_mmo_gather_base"), "the gated file never loaded");
        assertNull(without.pool().definition("harvest_feast_mmo_gather_base_copper"), "nor did its family");
        assertFalse(codes(without.issues()).contains("UNKNOWN_BASE"),
                "the refused id is the folded one the generator names, not the engine's filename key: "
                        + without.issues());

        ModGates.useProbeForTests(param -> 1.0);
        foldAsTheLoadHandlerDoes(files);
        QuestAssetStore.Resolution with = STORE.resolve(null);

        assertNotNull(with.pool().definition("harvest_feast_mmo_gather_base_copper"),
                "with the MMO the same Base resolves, so the generator does name the store's own id");
        assertFalse(codes(with.issues()).contains("UNKNOWN_BASE"), with.issues().toString());
    }
}
