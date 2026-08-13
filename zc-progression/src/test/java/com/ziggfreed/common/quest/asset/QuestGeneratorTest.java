package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.asset.QuestGeneratorExpander.Expansion;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The generator's contract: what it writes, how many, and above all that what it writes is
 * INDISTINGUISHABLE from the same quest written by hand.
 *
 * <p>That last one is the release gate ({@link ByteEquivalence}). The whole design rests on a
 * generator emitting ordinary child quests and merging nothing itself; the day it starts folding
 * fields on its own, generated content silently stops behaving like authored content, and every
 * later bug looks like a quest engine bug. So the test authors both forms and compares the JSON AND
 * the folded result.
 */
class QuestGeneratorTest {

    private QuestEnumeratorRegistry enumerators;

    @BeforeEach
    void freshRegistries() {
        enumerators = new QuestEnumeratorRegistry();
        QuestAssetStore.getInstance().mergeQuests(Map.of());
        QuestAssetStore.getInstance().mergeGenerators(Map.of());
    }

    static QuestGeneratorAsset generator(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestGeneratorAsset.class, id, null);
        return QuestGeneratorAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }

    private static List<String> ids(Expansion expansion) {
        return expansion.bodies().stream().map(GeneratedQuestBody::id).toList();
    }

    private static List<String> codes(List<Finding> issues) {
        return issues.stream().map(Finding::code).toList();
    }

    // ==================== the walk ====================

    @Nested
    class TheWalk {

        @Test
        void twoAxesProduceEveryCombination() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "gather_base", "IdPattern": "gather_{material}_{tier}",
                      "ForEach": [ { "Token": "material", "Values": ["copper", "iron", "cobalt"] },
                                   { "Token": "tier", "Values": [1, 2] } ],
                      "Child": { "Objectives": { "collect": { "Target": "{material}_Ore" } } } }
                    """, "ladder"), enumerators);

            assertEquals(6, expansion.bodies().size(), "three materials times two tiers");
            assertEquals(List.of("gather_copper_1", "gather_copper_2", "gather_iron_1",
                            "gather_iron_2", "gather_cobalt_1", "gather_cobalt_2"), ids(expansion),
                    "the first axis varies slowest, so a generated set reads in authored order");
            assertTrue(expansion.issues().isEmpty());
        }

        @Test
        void anObjectRowBindsSeveralTokensAtOnce() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{tier}",
                      "ForEach": [ { "Token": "tier", "Values": [ {"tier": "one", "amount": 10},
                                                                  {"tier": "two", "amount": 25} ] } ],
                      "Child": { "Objectives": { "collect": { "Amount": "{amount}" } } } }
                    """, "tiers"), enumerators);

            assertEquals(List.of("q_one", "q_two"), ids(expansion));
            assertEquals(10, amountOf(expansion.bodies().get(0)),
                    "values that belong together are stated together, not reconstructed from two axes");
            assertEquals(25, amountOf(expansion.bodies().get(1)));
        }

        @Test
        void aWholeValueTokenKeepsItsTypeSoANumberStaysANumber() throws Exception {
            GeneratedQuestBody body = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{tier}",
                      "ForEach": [ { "Token": "tier", "Values": [ {"tier": 1, "amount": 25} ] } ],
                      "Child": { "Objectives": { "collect": { "Amount": "{amount}",
                                                              "Target": "ore_tier_{tier}" } } } }
                    """, "typed"), enumerators).bodies().get(0);

            JsonObject collect = body.body().getAsJsonObject("Objectives").getAsJsonObject("collect");
            assertTrue(collect.get("Amount").getAsJsonPrimitive().isNumber(),
                    "a value that is exactly one token takes that token's own type, so it can fill a number");
            assertEquals("ore_tier_1", collect.get("Target").getAsString(),
                    "the same token inside a longer string is spliced in as text");
        }

        @Test
        void tokensAreSubstitutedInObjectKeysToo() throws Exception {
            GeneratedQuestBody body = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{material}",
                      "ForEach": [ { "Token": "material", "Values": ["copper"] } ],
                      "Child": { "Objectives": { "collect_{material}": { "Target": "{material}" } } } }
                    """, "keys"), enumerators).bodies().get(0);

            assertTrue(body.body().getAsJsonObject("Objectives").has("collect_copper"));
        }

        @Test
        void everyBodyCarriesItsParentAndNothingIsMergedInAdvance() throws Exception {
            GeneratedQuestBody body = QuestGeneratorExpander.expand(generator("""
                    { "Base": "Gather_Base", "IdPattern": "q_{n}",
                      "ForEach": [ { "Token": "n", "Values": ["a"] } ],
                      "Child": { "Listing": { "Category": "gathering" } } }
                    """, "g"), enumerators).bodies().get(0);

            assertEquals("gather_base", body.body().get("Parent").getAsString(),
                    "the body is an ordinary child quest, Parent and all");
            assertEquals(1, body.body().getAsJsonObject("Listing").size(),
                    "and carries only what the Child authored - the base's fields are merged at decode, "
                            + "by the same inheritance a hand-written child gets");
        }

        private static int amountOf(GeneratedQuestBody body) {
            return body.body().getAsJsonObject("Objectives").getAsJsonObject("collect")
                    .get("Amount").getAsInt();
        }
    }

    // ==================== value sources ====================

    @Nested
    class Sources {

        @Test
        void aRegisteredSourceSuppliesTheValuesAndSeesItsFilter() throws Exception {
            List<Map<String, String>> seen = new ArrayList<>();
            enumerators.register("yourmod:ores", filter -> {
                seen.add(filter);
                return List.of(QuestAxisRow.of("copper"), QuestAxisRow.of("iron"));
            });

            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{material}",
                      "ForEach": [ { "Token": "material", "Source": "yourmod:ores",
                                     "Filter": { "Rarity": "common" } } ],
                      "Child": { "Objectives": { "collect": { "Target": "{material}" } } } }
                    """, "sourced"), enumerators);

            assertEquals(List.of("q_copper", "q_iron"), ids(expansion));
            assertEquals(List.of(Map.of("Rarity", "common")), seen,
                    "the filter is forwarded verbatim; nothing in the library reads it");
        }

        @Test
        void aSourceMayBindSeveralTokensPerRow() throws Exception {
            enumerators.register("yourmod:tiers", filter -> List.of(
                    QuestAxisRow.builder().put("tier", "one").put("amount", 10L).build(),
                    QuestAxisRow.builder().put("tier", "two").put("amount", 25L).build()));

            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{tier}",
                      "ForEach": [ { "Source": "yourmod:tiers" } ],
                      "Child": { "Objectives": { "collect": { "Amount": "{amount}" } } } }
                    """, "sourced"), enumerators);

            assertEquals(List.of("q_one", "q_two"), ids(expansion));
            assertEquals(25, expansion.bodies().get(1).body().getAsJsonObject("Objectives")
                    .getAsJsonObject("collect").get("Amount").getAsInt(),
                    "a typed row binding survives as a number");
        }

        @Test
        void anUnregisteredSourceWritesNothingAndSaysSo() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{n}",
                      "ForEach": [ { "Token": "n", "Source": "yourmod:absent" } ],
                      "Child": { } }
                    """, "sourced"), enumerators);

            assertTrue(expansion.bodies().isEmpty());
            assertEquals(List.of("UNKNOWN_SOURCE"), codes(expansion.issues()));
            assertEquals(Severity.WARNING, expansion.issues().get(0).severity(),
                    "the mod that owns the source may simply not be installed, which is not an error");
        }

        @Test
        void aThrowingSourceIsReportedRatherThanTakingTheLoadDown() throws Exception {
            enumerators.register("yourmod:broken", filter -> {
                throw new IllegalStateException("no list today");
            });

            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{n}",
                      "ForEach": [ { "Token": "n", "Source": "yourmod:broken" } ],
                      "Child": { } }
                    """, "sourced"), enumerators);

            assertTrue(expansion.bodies().isEmpty());
            assertEquals(List.of("SOURCE_FAILED"), codes(expansion.issues()));
        }
    }

    // ==================== findings ====================

    @Nested
    class Findings {

        @Test
        void anEmptyAxisWritesNothingAndWarns() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{n}",
                      "ForEach": [ { "Token": "n", "Values": [] } ],
                      "Child": { } }
                    """, "g"), enumerators);

            assertTrue(expansion.bodies().isEmpty());
            assertEquals(List.of("EMPTY_AXIS"), codes(expansion.issues()));
        }

        @Test
        void aTokenNothingBindsIsAnErrorAndThatQuestIsSkipped() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{material}",
                      "ForEach": [ { "Token": "material", "Values": ["copper"] } ],
                      "Child": { "Objectives": { "collect": { "Target": "{material}_{missing}" } } } }
                    """, "g"), enumerators);

            assertTrue(expansion.bodies().isEmpty(), "a half-written quest is not shipped");
            assertTrue(codes(expansion.issues()).contains("UNRESOLVED_TOKEN"));
            assertTrue(expansion.issues().stream()
                            .anyMatch(i -> i.severity() == Severity.ERROR),
                    "an unfilled placeholder is nobody's intention");
        }

        @Test
        void anIdPatternTooCoarseToTellTwoCombinationsApartIsReported() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{material}",
                      "ForEach": [ { "Token": "material", "Values": ["copper"] },
                                   { "Token": "tier", "Values": [1, 2] } ],
                      "Child": { } }
                    """, "g"), enumerators);

            assertEquals(1, expansion.bodies().size(), "only one of the two can exist");
            assertTrue(codes(expansion.issues()).contains("DUPLICATE_ID"));
        }

        @Test
        void aBoundTokenNobodyUsesIsWorthSayingOutLoud() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Base": "base", "IdPattern": "q_{material}_{tier}",
                      "ForEach": [ { "Token": "material", "Values": ["copper"] },
                                   { "Token": "tier", "Values": [1] },
                                   { "Token": "unused", "Values": ["x"] } ],
                      "Child": { } }
                    """, "g"), enumerators);

            assertEquals(1, expansion.bodies().size());
            assertTrue(codes(expansion.issues()).contains("UNUSED_TOKEN"),
                    "an axis nothing reads only multiplies the count");
        }

        @Test
        void theHalfAuthoredGeneratorsAreCaughtBeforeTheyWriteAnything() throws Exception {
            assertEquals(List.of("MISSING_BASE"), codes(QuestGeneratorExpander.expand(
                    generator("{ \"IdPattern\": \"q\", \"Child\": {} }", "g"), enumerators).issues()));
            assertEquals(List.of("MISSING_CHILD"), codes(QuestGeneratorExpander.expand(
                    generator("{ \"Base\": \"b\", \"IdPattern\": \"q\" }", "g"), enumerators).issues()));
            assertEquals(List.of("MISSING_ID_PATTERN"), codes(QuestGeneratorExpander.expand(
                    generator("{ \"Base\": \"b\", \"Child\": {} }", "g"), enumerators).issues()));
            assertEquals(List.of("NO_AXES"), codes(QuestGeneratorExpander.expand(
                    generator("{ \"Base\": \"b\", \"IdPattern\": \"q\", \"Child\": {} }", "g"),
                    enumerators).issues()));
        }

        @Test
        void aDisabledGeneratorIsSilentRatherThanReported() throws Exception {
            Expansion expansion = QuestGeneratorExpander.expand(generator("""
                    { "Enabled": false, "Base": "base", "IdPattern": "q", "Child": { } }
                    """, "g"), enumerators);

            assertTrue(expansion.bodies().isEmpty());
            assertTrue(expansion.issues().isEmpty(), "switching a family off is a decision, not a mistake");
        }
    }

    // ==================== the release gate ====================

    /**
     * A generator writes exactly what a person would have written by hand. Both halves matter: the
     * JSON it emits, and the quest that JSON folds into once its {@code Parent} is applied.
     */
    @Nested
    class ByteEquivalence {

        private static final String BASE = """
                { "Abstract": true,
                  "Listing": { "Category": "gathering", "Tags": ["daily"] },
                  "Flow": { "AutoTrack": true, "Sequential": true },
                  "Npc": { "ViewId": "guide", "TurnInId": "giver" },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Amount": 10, "Order": 1 },
                                  "hand_in": { "Kind": "TURN_IN", "Amount": 10, "Order": 2 } },
                  "Rewards": [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ] }
                """;

        /** What a person writes for the copper tier-two quest. */
        private static final String HAND_AUTHORED = """
                { "Parent": "gather_base",
                  "Text": { "TitleKey": "quest.gather.copper.t2.title" },
                  "Objectives": { "collect": { "Target": "Copper_Ore", "Amount": 25 },
                                  "hand_in": { "Target": "Copper_Ore", "Amount": 25 } } }
                """;

        /** The generator that writes the same thing, plus its siblings. */
        private static final String GENERATOR = """
                { "Base": "gather_base", "IdPattern": "gather_{material}_t{tier}",
                  "ForEach": [ { "Token": "material", "Values": [ {"material": "copper", "ore": "Copper_Ore"},
                                                                  {"material": "iron",   "ore": "Iron_Ore"} ] },
                               { "Token": "tier", "Values": [ {"tier": 2, "amount": 25} ] } ],
                  "Child": {
                    "Text": { "TitleKey": "quest.gather.{material}.t{tier}.title" },
                    "Objectives": { "collect": { "Target": "{ore}", "Amount": "{amount}" },
                                    "hand_in": { "Target": "{ore}", "Amount": "{amount}" } } } }
                """;

        @Test
        void theEmittedBodyIsTheHandAuthoredFile() throws Exception {
            GeneratedQuestBody generated = firstBody();

            assertEquals(JsonParser.parseString(HAND_AUTHORED).getAsJsonObject(), generated.body(),
                    "the generator emits a quest file, not a merged quest");
            assertEquals("gather_copper_t2", generated.id());
        }

        @Test
        void bothFoldToTheSameQuestThroughTheSameInheritance() throws Exception {
            QuestAsset base = decodeRoot(BASE, "gather_base");
            QuestAsset byHand = QuestAssetCodecTest.decode(HAND_AUTHORED, "gather_copper_t2",
                    "gather_base", base);
            QuestAsset generated = QuestAssetCodecTest.decode(firstBody().body().toString(),
                    "gather_copper_t2", "gather_base", base);

            assertEquals(describe(byHand.toDefinition(null)), describe(generated.toDefinition("ladder")),
                    "a generated quest must be indistinguishable from the same quest written by hand");
        }

        @Test
        void theWholePoolResolvesThroughTheStoreTheSameWay() throws Exception {
            QuestAssetStore store = QuestAssetStore.getInstance();
            store.mergeQuests(Map.of(
                    "gather_base", decodeRoot(BASE, "gather_base"),
                    "hand_copper_t2", QuestAssetCodecTest.decode(HAND_AUTHORED, "hand_copper_t2",
                            "gather_base", decodeRoot(BASE, "gather_base"))));
            store.mergeGenerators(Map.of("ladder", generator(GENERATOR, "ladder")));

            QuestAssetStore.Resolution resolution = store.resolve(new QuestEnumeratorRegistry());
            QuestPool pool = resolution.pool();

            assertTrue(resolution.issues().isEmpty(), "nothing to report: " + codes(resolution.issues()));
            assertNull(pool.definition("gather_base"), "an Abstract quest is a Parent target, never an offer");
            assertEquals(List.of("gather_copper_t2", "gather_iron_t2"), pool.generatedIds());
            assertEquals(3, pool.size(), "the hand-authored quest plus the two generated ones");

            assertEquals(describe(pool.definition("hand_copper_t2")),
                    describe(pool.definition("gather_copper_t2")),
                    "same quest, whichever way it was written");
            assertNotNull(pool.definition("gather_iron_t2"));
            assertFalse(pool.definition("hand_copper_t2").isGenerated());
            assertTrue(pool.definition("gather_copper_t2").isGenerated());
        }

        @Test
        void anAuthoredFileWinsACollisionWithAGeneratedOne() throws Exception {
            QuestAssetStore store = QuestAssetStore.getInstance();
            QuestAsset base = decodeRoot(BASE, "gather_base");
            store.mergeQuests(Map.of(
                    "gather_base", base,
                    "gather_copper_t2", QuestAssetCodecTest.decode("""
                            { "Parent": "gather_base", "Text": { "TitleKey": "quest.special.title" },
                              "Objectives": { "collect": { "Target": "Copper_Ore" } } }
                            """, "gather_copper_t2", "gather_base", base)));
            store.mergeGenerators(Map.of("ladder", generator(GENERATOR, "ladder")));

            QuestAssetStore.Resolution resolution = store.resolve(new QuestEnumeratorRegistry());

            assertEquals("quest.special.title", resolution.pool().definition("gather_copper_t2").titleKey(),
                    "writing the file is how one member of a family is made special");
            assertFalse(resolution.pool().definition("gather_copper_t2").isGenerated());
            assertTrue(codes(resolution.issues()).contains("ID_COLLISION"),
                    "and the author is told, because the other possibility is a careless IdPattern");
        }

        @Test
        void aGeneratorWhoseBaseNobodyAuthoredIsReported() throws Exception {
            QuestAssetStore store = QuestAssetStore.getInstance();
            store.mergeQuests(Map.of());
            store.mergeGenerators(Map.of("ladder", generator(GENERATOR, "ladder")));

            QuestAssetStore.Resolution resolution = store.resolve(new QuestEnumeratorRegistry());

            assertEquals(0, resolution.pool().size());
            assertTrue(codes(resolution.issues()).contains("UNKNOWN_BASE"));
        }

        private static GeneratedQuestBody firstBody() throws IOException {
            return QuestGeneratorExpander.expand(generator(GENERATOR, "ladder"),
                    new QuestEnumeratorRegistry()).bodies().get(0);
        }

        /** Everything about a folded quest except which file it came from. */
        private static String describe(QuestDefinition definition) {
            String objectives = definition.quest().objectives().stream()
                    .map(QuestGeneratorTest::describe)
                    .collect(Collectors.joining("; "));
            return "title=" + definition.titleKey()
                    + " flavor=" + definition.flavorKey()
                    + " category=" + definition.category()
                    + " sort=" + definition.sortOrder()
                    + " npc=" + definition.npcViewId()
                    + " turnIn=" + definition.turnInNpcId()
                    + " tags=" + definition.quest().tags()
                    + " autoTrack=" + definition.quest().autoTrack()
                    + " autoClaim=" + definition.quest().autoClaim()
                    + " sequential=" + definition.quest().sequential()
                    + " repeat=" + definition.quest().repeat()
                    + " rewards=" + definition.quest().rewards()
                    + " objectives=[" + objectives + "]";
        }
    }

    private static String describe(ObjectiveDef objective) {
        return objective.id() + ":" + objective.kind() + ":" + objective.target() + ":"
                + objective.matchMode() + ":" + objective.amount() + ":" + objective.order() + ":"
                + objective.turnInLockId();
    }
}
