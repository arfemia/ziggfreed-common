package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static com.ziggfreed.common.quest.asset.QuestGeneratorTest.generator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.validation.Finding;

/**
 * The contributed layer on {@link QuestAssetStore}: a consumer hands in ONE whole layer of decoded
 * quests, and the fold lays it over the loaded files.
 *
 * <p>What is pinned is the precedence and the reporting. The contributed layer wins per id
 * SILENTLY - a converted file over a pack's is what a layer is for - while an id twice inside the
 * layer is reported by name, exactly as two loaded files on one id are. The layer is replaced
 * whole by the next hand-in and taken away by an empty one, so a reload never appends. Generators
 * expand against the composed view, so a contributed base is inherited from and an authored id in
 * either layer beats a generated one.
 */
class QuestAssetStoreContributionTest {

    private static final QuestAssetStore STORE = QuestAssetStore.getInstance();

    @BeforeEach
    void emptyStore() {
        STORE.mergeQuests(Map.of());
        STORE.mergeGenerators(Map.of());
        STORE.mergeContributed(Map.of());
    }

    @AfterEach
    void leaveTheStoreEmpty() {
        emptyStore();
    }

    static QuestAsset quest(String id, long amount) throws Exception {
        return decodeRoot("{ \"Objectives\": { \"collect\": { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\","
                + " \"Amount\": " + amount + " } } }", id);
    }

    static long amountOf(QuestPool pool, String id) {
        QuestDefinition definition = pool.definition(id);
        assertNotNull(definition, id + " should be in the pool");
        return definition.quest().objective("collect").amount();
    }

    static List<String> codes(List<Finding> issues) {
        return issues.stream().map(Finding::code).toList();
    }

    // ==================== precedence ====================

    @Test
    void theContributedLayerFoldsOverTheLoadedFilesAndWinsPerIdSilently() throws Exception {
        STORE.mergeQuests(Map.of("shared_one", quest("shared_one", 10), "base_only", quest("base_only", 1)));
        STORE.mergeContributed(Map.of(
                "shared_one", quest("shared_one", 20),
                "contributed_only", quest("contributed_only", 2)));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);
        QuestPool pool = resolution.pool();

        assertEquals(20L, amountOf(pool, "shared_one"), "the contributed layer wins the id");
        assertEquals(1L, amountOf(pool, "base_only"), "a loaded file nothing overrides still folds");
        assertEquals(2L, amountOf(pool, "contributed_only"), "a contributed id nothing else names still folds");
        assertTrue(resolution.issues().isEmpty(),
                "an override by layer is what a layer is for, so it is not reported: " + resolution.issues());
    }

    // ==================== idempotence ====================

    @Test
    void theLayerIsReplacedWholeByTheNextHandInAndTakenAwayByAnEmptyOne() throws Exception {
        STORE.mergeContributed(Map.of("first", quest("first", 1)));
        STORE.mergeContributed(Map.of("second", quest("second", 2)));

        QuestPool replaced = STORE.resolve(null).pool();
        assertNull(replaced.definition("first"), "the earlier hand-in is gone, not appended to");
        assertNotNull(replaced.definition("second"));

        STORE.mergeContributed(Map.of());
        assertNull(STORE.resolve(null).pool().definition("second"));
    }

    @Test
    void handingInTheSameLayerTwiceIsOneLayer() throws Exception {
        STORE.mergeContributed(Map.of("q", quest("q", 1)));
        STORE.mergeContributed(Map.of("q", quest("q", 1)));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);
        assertEquals(1L, amountOf(resolution.pool(), "q"));
        assertTrue(resolution.issues().isEmpty(), "a re-contribution is a replacement, not a clash");
    }

    // ==================== reporting ====================

    @Test
    void aDuplicateInsideTheContributedLayerIsReportedAtEveryFold() throws Exception {
        // Two map keys whose assets both carry the id 'dup': the id is what files the entry.
        STORE.mergeContributed(Map.of("a", quest("dup", 1), "b", quest("dup", 2)));

        List<Finding> issues = STORE.resolve(null).issues();

        assertTrue(codes(issues).contains("DUPLICATE_QUEST_ID"), "reported: " + issues);
        Finding finding = issues.stream().filter(f -> "DUPLICATE_QUEST_ID".equals(f.code())).findFirst().get();
        assertTrue(finding.message().contains("contributed"), "the layer is named: " + finding.message());
        assertEquals("dup", finding.sourceId());
        assertTrue(codes(STORE.resolve(null).issues()).contains("DUPLICATE_QUEST_ID"),
                "the finding is replayed by the next fold, not consumed by the first");
    }

    // ==================== generators over the composed view ====================

    @Test
    void aGeneratorInheritsFromAContributedBaseAndAnAuthoredIdInEitherLayerBeatsAGeneratedOne() throws Exception {
        STORE.mergeContributed(Map.of("gather_base", quest("gather_base", 5)));
        STORE.mergeGenerators(Map.of("ladder", generator("""
                { "Base": "gather_base", "IdPattern": "gather_{material}",
                  "ForEach": [ { "Token": "material", "Values": ["copper", "iron"] } ],
                  "Child": { "Objectives": { "collect": { "Target": "{material}_Ore" } } } }
                """, "ladder")));

        QuestAssetStore.Resolution generated = STORE.resolve(null);
        assertNotNull(generated.pool().definition("gather_copper"), "a contributed base is inherited from");
        assertEquals("copper_Ore", generated.pool().definition("gather_copper").quest().objective("collect").target());
        assertEquals(5L, amountOf(generated.pool(), "gather_iron"), "and the base's own leaves come through");
        assertFalse(codes(generated.issues()).contains("UNKNOWN_BASE"));

        STORE.mergeContributed(Map.of(
                "gather_base", quest("gather_base", 5),
                "gather_copper", quest("gather_copper", 99)));
        QuestAssetStore.Resolution overridden = STORE.resolve(null);
        assertEquals(99L, amountOf(overridden.pool(), "gather_copper"),
                "an authored id in the contributed layer beats the generated one");
        assertTrue(codes(overridden.issues()).contains("ID_COLLISION"),
                "and the collision is reported, the same as for a loaded file");
    }

    // ==================== the rules of the fold apply to contributed bodies ====================

    @Test
    void anAbstractContributedQuestIsNotFoldedAndAnAuthoredRepeatIsAudited() throws Exception {
        QuestAsset skeleton = decodeRoot("{ \"Abstract\": true, \"Objectives\": { \"collect\": "
                + "{ \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }", "skeleton");
        QuestAsset odd = decodeRoot("{ \"Repeat\": { \"CooldownFrom\": \"Sometime\" }, \"Objectives\": { \"collect\": "
                + "{ \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\" } } }", "odd");
        STORE.mergeContributed(Map.of("skeleton", skeleton, "odd", odd));

        QuestAssetStore.Resolution resolution = STORE.resolve(null);

        assertNull(resolution.pool().definition("skeleton"), "a skeleton exists only to be inherited from");
        assertNotNull(resolution.pool().definition("odd"));
        assertTrue(codes(resolution.issues()).contains("REPEAT_UNKNOWN_COOLDOWN_FROM"),
                "a contributed body's authored Repeat is audited exactly like a loaded one");
    }

    @Test
    void theLoadedViewAndTheComposedViewAreTwoDifferentReads() throws Exception {
        STORE.mergeQuests(Map.of("loaded", quest("loaded", 1)));
        STORE.mergeContributed(Map.of("contributed", quest("contributed", 2)));

        assertEquals(List.of("loaded"), List.copyOf(STORE.assets().keySet()),
                "assets() is the loaded layer alone");
        assertTrue(STORE.composedAssets().containsKey("loaded") && STORE.composedAssets().containsKey("contributed"),
                "composedAssets() is what a fold reads");
    }
}
