package com.ziggfreed.common.progress.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * The {@code Meta} extension leaf: a namespace a consumer owns rides through the schema untouched,
 * survives {@code Parent} inheritance per namespace, and decodes into a consumer's own codec with
 * every unclaimed key reported.
 *
 * <p>Both content types carry the identical field, so both are exercised: a divergence between them
 * would be exactly the drift the shared group exists to prevent.
 */
class ContentMetaTest {

    private static final String NS = "yourmod";

    static QuestAsset quest(String json, String id, @Nullable String parentId, @Nullable QuestAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, id, parentId);
        return QuestAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static AchievementAsset achievement(String json, String id, @Nullable String parentId,
            @Nullable AchievementAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(AchievementAsset.class, id, parentId);
        return AchievementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    // ==================== a consumer's own block, decoded ====================

    /** Exactly the shape a consumer declares for its own namespace: an ordinary builder codec. */
    static final class Block {

        @Nullable String chain;
        @Nullable Integer tier;
        @Nullable Boolean serverFirst;

        static final BuilderCodec<Block> CODEC = BuilderCodec.builder(Block.class, Block::new)
                .append(new KeyedCodec<>("Chain", Codec.STRING, false), (o, v) -> o.chain = v, o -> o.chain).add()
                .append(new KeyedCodec<>("Tier", Codec.INTEGER, false), (o, v) -> o.tier = v, o -> o.tier).add()
                .append(new KeyedCodec<>("ServerFirst", Codec.BOOLEAN, false),
                        (o, v) -> o.serverFirst = v, o -> o.serverFirst).add()
                .build();

        Block() {
        }
    }

    @Nested
    class Decoding {

        @Test
        void aConsumerReadsItsOwnNamespaceThroughItsOwnCodec() throws Exception {
            QuestAsset asset = quest("""
                    { "Meta": { "yourmod": { "Chain": "prospecting", "Tier": 2, "ServerFirst": true } } }
                    """, "gather_copper", null, null);

            Block block = ContentMeta.decode(asset.metaOrEmpty(), NS, Block.CODEC, null);

            assertNotNull(block);
            assertEquals("prospecting", block.chain);
            assertEquals(2, block.tier);
            assertEquals(Boolean.TRUE, block.serverFirst);
        }

        @Test
        void aNamespaceNobodyAskedAboutIsNeitherReadNorLost() throws Exception {
            AchievementAsset asset = achievement("""
                    { "Meta": { "yourmod":  { "Chain": "prospecting" },
                                "othermod": { "Whatever": [1, 2, 3], "Nested": { "Deep": "kept" } } } }
                    """, "prospector", null, null);

            assertNull(ContentMeta.decode(asset.metaOrEmpty(), "nobody", Block.CODEC, null),
                    "a namespace the file never authored must read as absent");

            JsonElement other = ContentMeta.block(asset.metaOrEmpty(), "othermod");
            assertNotNull(other, "an unknown namespace must ride along untouched");
            assertEquals("kept",
                    other.getAsJsonObject().getAsJsonObject("Nested").get("Deep").getAsString(),
                    "the block must be kept exactly as authored, however deep");
        }

        @Test
        void aKeyTheConsumersCodecDoesNotKnowIsReportedAndTheRestStillDecodes() throws Exception {
            QuestAsset asset = quest("""
                    { "Meta": { "yourmod": { "Chain": "prospecting", "Teir": 2 } } }
                    """, "gather_copper", null, null);

            List<String> unknown = new ArrayList<>();
            Block block = ContentMeta.decode(asset.metaOrEmpty(), NS, Block.CODEC, unknown::add);

            assertNotNull(block, "one unclaimed key must never cost the whole block");
            assertEquals("prospecting", block.chain);
            assertTrue(unknown.stream().anyMatch(key -> key.contains("Teir")),
                    "the typo must be named, not swallowed: " + unknown);
        }

        @Test
        void aNamespaceIsMatchedWithoutRegardToCase() throws Exception {
            QuestAsset asset = quest("""
                    { "Meta": { "YourMod": { "Chain": "prospecting" } } }
                    """, "gather_copper", null, null);

            Block block = ContentMeta.decode(asset.metaOrEmpty(), NS, Block.CODEC, null);
            assertNotNull(block);
            assertEquals("prospecting", block.chain);
        }

        @Test
        void contentCarryingNoMetaAtAllReadsAsEmpty() throws Exception {
            QuestAsset asset = quest("{ \"Listing\": { \"Category\": \"gathering\" } }", "plain", null, null);

            assertTrue(asset.metaOrEmpty().isEmpty());
            assertNull(ContentMeta.decode(asset.metaOrEmpty(), NS, Block.CODEC, null));
            assertTrue(asset.toDefinition(null).meta().isEmpty(),
                    "the folded definition must agree with the asset");
        }
    }

    // ==================== inheritance ====================

    @Nested
    class Inheritance {

        @Test
        void aChildInheritsEveryNamespaceItDoesNotMention() throws Exception {
            QuestAsset parent = quest("""
                    { "Meta": { "yourmod": { "Chain": "prospecting" }, "othermod": { "Kept": true } } }
                    """, "gather_base", null, null);

            QuestAsset child = quest("{ \"Listing\": { \"SortOrder\": 20 } }",
                    "gather_copper", "gather_base", parent);

            Block block = ContentMeta.decode(child.metaOrEmpty(), NS, Block.CODEC, null);
            assertNotNull(block);
            assertEquals("prospecting", block.chain);
            assertNotNull(ContentMeta.block(child.metaOrEmpty(), "othermod"));
        }

        @Test
        void aNamespaceTheChildAuthorsReplacesTheParentsBlockWhole() throws Exception {
            QuestAsset parent = quest("""
                    { "Meta": { "yourmod": { "Chain": "prospecting", "Tier": 1 } } }
                    """, "gather_base", null, null);

            QuestAsset child = quest("""
                    { "Meta": { "yourmod": { "Tier": 2 } } }
                    """, "gather_copper", "gather_base", parent);

            Block block = ContentMeta.decode(child.metaOrEmpty(), NS, Block.CODEC, null);
            assertNotNull(block);
            assertEquals(2, block.tier, "the child's own key must win");
            assertNull(block.chain,
                    "a block is replaced whole: a key the child omitted must NOT be inherited");
        }

        @Test
        void aChildMayAddANamespaceTheParentNeverHad() throws Exception {
            AchievementAsset parent = achievement("""
                    { "Meta": { "yourmod": { "Chain": "prospecting" } } }
                    """, "prospector_base", null, null);

            AchievementAsset child = achievement("""
                    { "Meta": { "othermod": { "Added": true } } }
                    """, "prospector", "prospector_base", parent);

            assertNotNull(ContentMeta.block(child.metaOrEmpty(), "othermod"));
            Block inherited = ContentMeta.decode(child.metaOrEmpty(), NS, Block.CODEC, null);
            assertNotNull(inherited, "the parent's other namespace must survive the addition");
            assertEquals("prospecting", inherited.chain);
        }
    }

    // ==================== the two carriers that are SHARED, not per consumer ====================

    /**
     * {@code Text.TextArgs} and {@code Listing.Chains} live in the shared schema rather than under a
     * namespace, because filling a key's slots and belonging to a ladder are things any consumer
     * wants. Both are proved on BOTH content types: the whole point of a shared group is that the
     * two cannot drift.
     */
    @Nested
    class SharedCarriers {

        @Test
        void textArgsFillTheSlotsOfBothKeys() throws Exception {
            QuestAsset asset = quest("""
                    { "Text": { "TitleKey": "q.title", "FlavorKey": "q.flavor",
                                "TextArgs": { "Title": [ "@amount" ], "Flavor": [ "@amount", "ore" ] } } }
                    """, "gather_copper", null, null);

            assertEquals(List.of("@amount"), asset.toDefinition(null).titleArgs());
            assertEquals(List.of("@amount", "ore"), asset.toDefinition(null).flavorArgs());
        }

        @Test
        void anAchievementSpellsTextArgsTheSameWay() throws Exception {
            AchievementAsset asset = achievement("""
                    { "Text": { "FlavorKey": "a.flavor", "TextArgs": { "Flavor": [ "@amount" ] } },
                      "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 10 } } }
                    """, "prospector", null, null);

            assertEquals(List.of("@amount"), asset.toDefinition().flavorArgs());
            assertEquals(List.of(), asset.toDefinition().titleArgs());
        }

        @Test
        void aSentinelIsAskedOfTheConsumerAndAnythingElseIsALiteral() {
            Object[] expanded = ContentTextAsset.expand(List.of("@amount", "@nobody", "ore"),
                    sentinel -> ContentTextAsset.ARG_AMOUNT.equals(sentinel) ? "10,000" : null);

            assertEquals(3, expanded.length);
            assertEquals("10,000", expanded[0]);
            assertEquals("@nobody", expanded[1],
                    "an unanswered sentinel must show, so an author sees they wrote one nothing provides");
            assertEquals("ore", expanded[2]);
        }

        @Test
        void oneItemMayBeARungOfSeveralLadders() throws Exception {
            AchievementAsset asset = achievement("""
                    { "Listing": { "Chains": [ { "Id": "Mining_Copper", "Tier": 3 },
                                               { "Id": "prospecting", "Tier": 1 } ] },
                      "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 10 } } }
                    """, "prospector", null, null);

            List<ContentListingAsset.ChainMembership> chains = asset.toDefinition().chains();
            assertEquals(2, chains.size());
            assertEquals("mining_copper", chains.get(0).getId(), "a ladder id is matched lower-cased");
            assertEquals(3, chains.get(0).tierOrZero());
            assertEquals(1, chains.get(1).tierOrZero());
        }

        @Test
        void aQuestSpellsChainsTheSameWay() throws Exception {
            QuestAsset asset = quest("""
                    { "Listing": { "Chains": [ { "Id": "onboarding", "Tier": 2 } ] } }
                    """, "gather_copper", null, null);

            assertEquals(1, asset.toDefinition(null).chains().size());
            assertEquals("onboarding", asset.toDefinition(null).chains().get(0).getId());
        }

        @Test
        void bothCarriersInheritLikeEveryOtherLeaf() throws Exception {
            AchievementAsset parent = achievement("""
                    { "Text": { "FlavorKey": "a.flavor", "TextArgs": { "Flavor": [ "@amount" ] } },
                      "Listing": { "Category": "gathering", "Chains": [ { "Id": "mining", "Tier": 1 } ] },
                      "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 10 } } }
                    """, "prospector_base", null, null);

            AchievementAsset child = achievement("{ \"Scoring\": { \"Points\": 20 } }",
                    "prospector", "prospector_base", parent);

            assertEquals(List.of("@amount"), child.toDefinition().flavorArgs());
            assertEquals(1, child.toDefinition().chains().size());
            assertEquals("gathering", child.toDefinition().category(),
                    "a sibling leaf of the group the shared base declares must survive too");
        }
    }

    // ==================== the folded definitions ====================

    @Nested
    class OnTheFoldedDefinition {

        @Test
        void aQuestDefinitionCarriesTheBlockThroughTheFold() throws Exception {
            QuestAsset asset = quest("""
                    { "Meta": { "yourmod": { "Chain": "prospecting" } } }
                    """, "gather_copper", null, null);

            Block block = ContentMeta.decode(asset.toDefinition(null).meta(), NS, Block.CODEC, null);
            assertNotNull(block);
            assertEquals("prospecting", block.chain);
        }

        @Test
        void anAchievementDefinitionCarriesTheBlockThroughTheFold() throws Exception {
            AchievementAsset asset = achievement("""
                    { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 10 } },
                      "Meta": { "yourmod": { "ServerFirst": true } } }
                    """, "prospector", null, null);

            Block block = ContentMeta.decode(asset.toDefinition().meta(), NS, Block.CODEC, null);
            assertNotNull(block);
            assertEquals(Boolean.TRUE, block.serverFirst);
            assertNotNull(asset.toDefinition().meta(NS), "the per-namespace accessor must agree");
        }
    }
}
