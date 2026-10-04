package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.entity.ItemReadings;
import com.ziggfreed.common.entity.TestItems;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * The portable {@code hytale:} vocabulary's contract in the one situation a unit JVM can honestly
 * exercise: <b>no live subject at all</b>. That is not a contrived case - a placement gate is
 * evaluated before anything stands there, and an asset validator runs with no player in hand - so
 * "answers null, never throws, never invents a zero" is the behaviour that decides whether a gate
 * authored on these ids is safe to evaluate anywhere.
 *
 * <p>The live-subject paths (a real hotbar, a real item asset) need a running server and are
 * covered by in-game smoke, matching the rest of this module's split. The ITEM family is the
 * exception: it reads a plain stack off the context rather than a live entity, so a real engine
 * stack built without a server exercises it here. {@code item_quality} and {@code item_stat} read
 * the LIVE quality and stat-channel asset maps, so their value tests seed those maps for the length
 * of the test ({@link EngineAssetStores}, the engine's own test technique).
 *
 * <p>The tests that build a real engine stack are tagged {@code engine-items}: an {@code ItemStack}
 * or {@code Item} can only be built under the engine's own log manager, so they run in the
 * {@code engineItemTest} task, and every other test here runs in the log-manager-less {@code test}
 * task a consumer mod's own tests run in (both set up in {@code gradle/zc-module.gradle}).
 */
class HytaleFactorsTest {

    private static FactorContext ctx(String param) {
        return FactorContext.builder().param(param).build();
    }

    @Test
    void registerIntoClaimsEveryPortableIdUnderTheGivenOwner() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        assertEquals(List.of(
                        HytaleFactors.HELD_ITEM,
                        HytaleFactors.HELD_TAG,
                        HytaleFactors.ITEM_DURABILITY_PERCENT,
                        HytaleFactors.ITEM_LEVEL,
                        HytaleFactors.ITEM_QUALITY,
                        HytaleFactors.ITEM_STAT,
                        HytaleFactors.PERMISSION,
                        HytaleFactors.STAT,
                        HytaleFactors.TOOL_DURABILITY_PERCENT,
                        HytaleFactors.TOOL_ITEM_LEVEL,
                        HytaleFactors.TOOL_POWER,
                        HytaleFactors.TOOL_QUALITY,
                        HytaleFactors.TOOL_TIER),
                registry.ids(),
                "the portable set is fixed - a new id here is a deliberate vocabulary addition");
        assertEquals("yourmod", registry.info().get(HytaleFactors.STAT).owner());
    }

    @Test
    void everyFactorAnswersNothingWithNoSubjectRatherThanZero() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : registry.ids()) {
            assertNull(registry.resolve(id, ctx("anything")),
                    id + " must be unresolvable with no subject, so a gate on it stays shut");
        }
    }

    @Test
    void aGateOnAnyPortableFactorFailsClosedWithNoSubject() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : registry.ids()) {
            assertEquals(id,
                    FactorConditions.firstFailure(
                            List.of(FactorCondition.of(id, "anything", null, null)), registry, ctx(null)),
                    id + " must fail even a bounds-less presence check when it cannot be read");
        }
    }

    @Test
    void aBlankParamIsNotEnoughForTheIdsThatAddressSomething() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        // stat / held_tag / held_item / permission all need a Param to name what they are asking
        // about; with none they cannot answer, which must read the same as any other unanswerable
        // factor.
        assertNull(registry.resolve(HytaleFactors.STAT, ctx(null)));
        assertNull(registry.resolve(HytaleFactors.HELD_TAG, ctx("   ")));
        assertNull(registry.resolve(HytaleFactors.HELD_ITEM, ctx(null)));
        assertNull(registry.resolve(HytaleFactors.PERMISSION, ctx("   ")));
    }

    @Tag("engine-items")
    @Test
    void aBlankParamIsNotEnoughForItemStatEvenWithAnItem() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        assertNull(registry.resolve(HytaleFactors.ITEM_STAT,
                FactorContext.builder().param("  ")
                        .item(TestItems.stack(sword(), 1, 1)).build()),
                "item_stat names a channel; with none it cannot answer even with an item in hand");
    }

    // ==================== the item family ====================

    private static Item sword() {
        return TestItems.item("Test_Sword", 14, 1);
    }

    private static FactorContext about(ItemStack item, String param) {
        return FactorContext.builder().param(param).item(item).build();
    }

    @Tag("engine-items")
    @Test
    void theItemFamilyReadsTheContextItemNotTheHand() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");
        ItemStack worn = TestItems.stack(sword(), 30, 120);

        assertEquals(14.0, registry.resolve(HytaleFactors.ITEM_LEVEL, about(worn, null)));
        assertEquals(25.0, registry.resolve(HytaleFactors.ITEM_DURABILITY_PERCENT, about(worn, null)));
        // The held-tool readings ask about the subject's HAND; an item in the context is not in
        // anybody's hand, so they still have nothing to read.
        assertNull(registry.resolve(HytaleFactors.TOOL_ITEM_LEVEL, about(worn, null)));
        assertNull(registry.resolve(HytaleFactors.TOOL_DURABILITY_PERCENT, about(worn, null)));
    }

    @Tag("engine-items")
    @Test
    void anItemThatTracksNoDurabilityReadsFullyIntact() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");
        ItemStack ingot = TestItems.stack(TestItems.item("Test_Ingot", 0, 0), 0, 0);

        assertEquals(100.0, registry.resolve(HytaleFactors.ITEM_DURABILITY_PERCENT, about(ingot, null)));
    }

    @Tag("engine-items")
    @Test
    void itemQualityReadsTheLiveQualityOfTheIndexTheStackCarries() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");
        ItemStack piece = TestItems.stack(TestItems.item("Test_Piece", 3, 2), 1, 1);

        try (EngineAssetStores.Swap ignored = EngineAssetStores.qualities(
                TestItems.quality(-1), TestItems.quality(10), TestItems.quality(25))) {
            assertEquals(25.0, registry.resolve(HytaleFactors.ITEM_QUALITY, about(piece, null)));
            assertEquals(0.0, registry.resolve(HytaleFactors.ITEM_QUALITY,
                            about(TestItems.stack(TestItems.item("Test_Plain", 1, 0), 1, 1), null)),
                    "the engine's default quality authors -1, floored to 0");
        }
    }

    /**
     * The quality split at the registry, on a stack stamped with quality index 1 (the
     * {@code ItemStack#withQuality} constructor, through {@code TestItems.requalified}) whose item
     * authors index 2. {@code item_quality} reads the stamp the stack carries. {@code tool_quality}'s
     * hand lookup needs a live hotbar, so the resolver's own reading of the held stack
     * ({@code toolQualityOfHeld}, which the resolver hands the held stack to) is driven directly,
     * against the same live quality map: the item's current quality.
     */
    @Tag("engine-items")
    @Test
    void itemQualityFollowsTheStackWhereTheToolReadingFollowsTheItem() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");
        ItemStack piece = TestItems.requalified(TestItems.item("Test_Hatchet", 3, 2), 1);

        try (EngineAssetStores.Swap ignored = EngineAssetStores.qualities(
                TestItems.quality(0), TestItems.quality(10), TestItems.quality(25))) {
            assertEquals(10.0, registry.resolve(HytaleFactors.ITEM_QUALITY, about(piece, null)),
                    "item_quality reads the quality stamped on the stack");
            assertEquals(25.0, HytaleFactors.toolQualityOfHeld(piece),
                    "tool_quality's reading of the held stack answers its item's current quality");
            assertNull(HytaleFactors.toolQualityOfHeld(null), "nothing held reads null");
        }
    }

    /**
     * Update 7's unstamped stack at the registry: made while its item's quality sat at index 1, read
     * after the item's quality moved to index 2. It carries no quality of its own, so both readings
     * answer the item's current quality.
     */
    @Tag("engine-items")
    @Test
    void anUnstampedStackReadsItsItemsCurrentQualityThroughBothReadings() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");
        ItemStack piece = TestItems.madeFrom(TestItems.item("Test_Hatchet", 3, 1),
                TestItems.item("Test_Hatchet", 3, 2), 1, 1);

        try (EngineAssetStores.Swap ignored = EngineAssetStores.qualities(
                TestItems.quality(0), TestItems.quality(10), TestItems.quality(25))) {
            assertEquals(25.0, registry.resolve(HytaleFactors.ITEM_QUALITY, about(piece, null)),
                    "item_quality follows the item's reload");
            assertEquals(25.0, HytaleFactors.toolQualityOfHeld(piece), "and agrees with tool_quality");
        }
    }

    /**
     * {@code StatIndexCache} keeps every id it resolves for the life of the JVM, so the channel id
     * seeded here is unique to this test (see {@link EngineAssetStores}).
     */
    @Tag("engine-items")
    @Test
    void itemStatReadsTheAdditiveAmountsTheItemAuthorsTowardALiveChannel() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");
        String channel = "Test_Item_Stat_Factor_Channel";
        int index = 3;
        ItemStack piece = TestItems.stack(TestItems.item("Test_Piece", 10, 1,
                TestItems.stats(index, TestItems.additive(3f), TestItems.multiplicative(2f)),
                TestItems.stats(index, TestItems.additive(4f)),
                null), 1, 1);

        try (EngineAssetStores.Swap ignored = EngineAssetStores.statChannels(
                "Test_Channel_A", "Test_Channel_B", "Test_Channel_C", channel)) {
            assertEquals(7.0, registry.resolve(HytaleFactors.ITEM_STAT, about(piece, channel)), 1e-9,
                    "armor 3 + weapon 4; the multiplier adds nothing");
            assertEquals(0.0, registry.resolve(HytaleFactors.ITEM_STAT, about(piece, "Test_Channel_A")),
                    "a live channel the item authors nothing toward is a real 0");
        }
    }

    @Tag("engine-items")
    @Test
    void theItemFamilyAnswersNothingWithNoItemInTheContext() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : List.of(HytaleFactors.ITEM_QUALITY, HytaleFactors.ITEM_LEVEL,
                HytaleFactors.ITEM_DURABILITY_PERCENT, HytaleFactors.ITEM_STAT)) {
            assertNull(registry.resolve(id, ctx("Health")), id + " has no item to read");
            assertNull(registry.resolve(id, about(ItemStack.EMPTY, "Health")),
                    id + " reads an empty stack as no item");
        }
    }

    /**
     * A permission is the one portable reading whose absent answer could plausibly be argued as a
     * definite "no", so the rule is pinned on its own: with nobody to ask - no subject at all, or a
     * subject that is not a player - it answers NOTHING. A {@code 0} there would let a "must not
     * hold this" bound pass for a mob, and would open a bounds-less gate on any entity in the world.
     */
    @Test
    void aPermissionWithNobodyToAskAnswersNothingRatherThanNo() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        Double unanswered = registry.resolve(HytaleFactors.PERMISSION, ctx("yourmod.shop.vip"));
        assertNull(unanswered);
        assertFalse(FactorCondition.of(HytaleFactors.PERMISSION, "yourmod.shop.vip", null, 0.0)
                        .accepts(unanswered),
                "even a 'must NOT hold it' bound stays shut when there is nobody to ask");
    }

    @Test
    void resolvingIsSideEffectFreeSoNoProviderIsEverCountedAsFailed() {
        FactorRegistry registry = new FactorRegistry();
        HytaleFactors.registerInto(registry, "yourmod");

        for (String id : registry.ids()) {
            registry.resolve(id, ctx("anything"));
        }

        registry.info().forEach((id, info) ->
                assertEquals(0, info.failures(), id + " answered null by DECIDING to, never by throwing"));
        assertTrue(registry.info().size() == registry.ids().size());
    }
}
