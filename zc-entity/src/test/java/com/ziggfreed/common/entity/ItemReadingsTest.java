package com.ziggfreed.common.entity;

import static com.ziggfreed.common.entity.TestItems.additive;
import static com.ziggfreed.common.entity.TestItems.item;
import static com.ziggfreed.common.entity.TestItems.madeFrom;
import static com.ziggfreed.common.entity.TestItems.multiplicative;
import static com.ziggfreed.common.entity.TestItems.quality;
import static com.ziggfreed.common.entity.TestItems.requalified;
import static com.ziggfreed.common.entity.TestItems.stack;
import static com.ziggfreed.common.entity.TestItems.stats;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.function.IntFunction;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * The one item reader, asked about real engine stacks and items (an item filled through its
 * protected fields, a stack made through the engine's own constructors, since a unit JVM has no item
 * store). Quality is read through an injected quality lookup, the pure core the live reader wraps
 * around the engine's quality asset map; every value asserted here is authored by the test itself.
 *
 * <p>Tagged {@code engine-items} as a whole: every test here builds a real engine item or stack,
 * which can only be done under the engine's own log manager, so the class runs in the
 * {@code engineItemTest} task (set up in {@code gradle/zc-module.gradle}).
 */
@Tag("engine-items")
class ItemReadingsTest {

    /** Quality index {@code i} resolves to a quality whose ordering value is {@code i * 10}. */
    private static final IntFunction<ItemQuality> TENS = i -> quality(i * 10);

    @Test
    void nothingToAskAboutReadsNothing() {
        assertNull(ItemReadings.quality((ItemStack) null, TENS));
        assertNull(ItemReadings.quality((Item) null, TENS));
        assertNull(ItemReadings.itemLevel((ItemStack) null));
        assertNull(ItemReadings.itemLevel((Item) null));
        assertNull(ItemReadings.durabilityPercent(null));
        assertNull(ItemReadings.statTotal((ItemStack) null, "Health"));
        assertNull(ItemReadings.durabilityPercent(ItemStack.EMPTY), "an empty stack is no stack");
        assertNull(ItemReadings.itemLevel(ItemStack.EMPTY));
    }

    @Test
    void wearReadsAsAPercentOfTheStack() {
        Item sword = item("Test_Sword", 5, 1);

        assertEquals(25.0, ItemReadings.durabilityPercent(stack(sword, 50, 200)));
        assertEquals(100.0, ItemReadings.durabilityPercent(stack(sword, 200, 200)));
        assertEquals(0.0, ItemReadings.durabilityPercent(stack(sword, 0, 200)));
    }

    @Test
    void aStackThatTracksNoDurabilityReadsAsUnworn() {
        Item ingot = item("Test_Ingot", 0, 0);

        assertEquals(100.0, ItemReadings.durabilityPercent(stack(ingot, 0, 0)),
                "an item that cannot wear is never worn, so a wear gate never rejects it");
    }

    @Test
    void itemLevelComesFromTheStacksItem() {
        Item sword = item("Test_Sword", 12, 1);

        assertEquals(12.0, ItemReadings.itemLevel(stack(sword, 1, 1)));
        assertEquals(12.0, ItemReadings.itemLevel(sword));
        assertEquals(0.0, ItemReadings.itemLevel(item("Test_Odd", -3, 1)), "floored at 0");
    }

    @Test
    void aRequalifiedStackReadsItsOwnQualityAndItsItemStillReadsTheItems() {
        Item sword = item("Test_Sword", 5, 2);
        ItemStack stack = requalified(sword, 4);

        assertEquals(40.0, ItemReadings.quality(stack, TENS), "a re-qualified stack reads as what it now is");
        assertEquals(20.0, ItemReadings.quality(stack.getItem(), TENS), "its item's own quality is untouched");
    }

    @Test
    void aStackCarryingNoIndexAtAllReadsItsItemsQuality() {
        Item sword = item("Test_Sword", 5, 2);
        ItemStack stack = requalified(sword, AssetMapWithIndexes.NOT_FOUND);

        assertEquals(20.0, ItemReadings.quality(stack, TENS),
                "the engine's own fallback, for a stack decoded with no Quality key");
    }

    @Test
    void aStackMadeFromAnItemCarriesACopyOfItsItemsQuality() {
        Item sword = item("Test_Sword", 5, 2);
        ItemStack stack = stack(sword, 1, 1);

        assertEquals(2, stack.getQualityIndex(), "the engine's constructor copies the item's index into the stack");
        assertEquals(20.0, ItemReadings.quality(stack, TENS));
        assertEquals(20.0, ItemReadings.quality(sword, TENS));
    }

    /**
     * The split between the two quality readings, on the one shape where they differ: a stack made
     * while its item's quality sat at index 2, read after the item's quality moved to index 4 (a
     * reload of the item's {@code Quality}, or the quality index order moving between boots). The
     * stack keeps the index it copied, so {@code hytale:item_quality}, which reads the STACK, answers
     * 20. {@code hytale:tool_quality} reads the held ITEM ({@code HeldItemUtil.heldItem} is the held
     * stack's {@code getItem()}), so it answers the item's current 40, exactly as it did in 2.1.x.
     */
    @Test
    void theToolReadingFollowsTheItemAndTheItemReadingFollowsTheStack() {
        Item whenMade = item("Test_Hatchet", 7, 2);
        Item reloaded = item("Test_Hatchet", 7, 4);
        ItemStack held = madeFrom(whenMade, reloaded, 10, 20);

        assertEquals(2, held.getQualityIndex(), "the stack keeps the index it was made with");
        assertEquals(4, held.getItem().getQualityIndex(), "its item reports the reloaded index");

        Double toolQuality = ItemReadings.quality(held.getItem(), TENS);
        Double itemQuality = ItemReadings.quality(held, TENS);
        assertEquals(40.0, toolQuality, "tool_quality reads the held item's CURRENT quality");
        assertEquals(20.0, itemQuality, "item_quality reads the quality index the stack carries");
        assertEquals(ItemReadings.itemLevel(held.getItem()), ItemReadings.itemLevel(held),
                "item level lives on the item alone, so the two paths cannot split on it");
    }

    @Test
    void theQualityIdNamesTheTierTheStackCarries_andTheItemsOwn() {
        Item sword = item("Test_Sword", 5, 2);
        ItemStack made = stack(sword, 1, 1);
        ItemStack stack = requalified(sword, 4);

        assertEquals("Test_Quality_20", ItemReadings.qualityId(made, TENS),
                "a stack made from its item names its item's tier");
        assertEquals("Test_Quality_40", ItemReadings.qualityId(stack, TENS),
                "a re-qualified stack names the tier it now carries");
        assertEquals("Test_Quality_20", ItemReadings.qualityId(stack.getItem(), TENS),
                "its item still names its own tier");
        assertEquals("Test_Quality_20", ItemReadings.qualityId(requalified(sword, AssetMapWithIndexes.NOT_FOUND), TENS),
                "a stack carrying no index at all names its item's tier, the engine's own fallback");
    }

    @Test
    void aQualityIdThatCannotBeNamedReadsNothing() {
        Item sword = item("Test_Sword", 5, 2);

        assertNull(ItemReadings.qualityId((ItemStack) null, TENS));
        assertNull(ItemReadings.qualityId((Item) null, TENS));
        assertNull(ItemReadings.qualityId(ItemStack.EMPTY, TENS), "an empty stack is no stack");
        assertNull(ItemReadings.qualityId(stack(sword, 1, 1), i -> null), "an index naming no loaded quality");
        assertNull(ItemReadings.qualityId(sword, i -> null));
        assertNull(ItemReadings.qualityId(sword, i -> {
            throw new IllegalStateException("no quality store");
        }), "a lookup that throws reads nothing, never a guess");
    }

    @Test
    void anUnresolvedOrNegativeQualityReadsZero() {
        Item sword = item("Test_Sword", 5, 2);

        assertEquals(0.0, ItemReadings.quality(sword, i -> null), "an index naming nothing reads 0");
        assertEquals(0.0, ItemReadings.quality(sword, i -> quality(-1)),
                "the engine's default quality authors -1, floored so it never drags a formula down");
    }

    /**
     * The held-tool readings are exactly their 2.1.x values. This recomputes the 2.1.x formulas
     * inline (quality and item level off the held ITEM, durability off the held stack) and holds the
     * paths the 2.2.0 resolvers take to them across a spread of qualities and levels, on stacks made
     * the way the engine makes them (each carrying a copy of its item's quality index). The tool
     * quality path is the item read; the item level and durability paths take the held stack.
     */
    @Test
    void theHeldToolPathsAreByteIdenticalTo21x() {
        for (int index = 0; index < 6; index++) {
            for (int level : new int[]{0, 1, 17, 80}) {
                Item tool = item("Test_Tool", level, index);
                ItemStack held = stack(tool, 40, 80);

                ItemQuality authored = TENS.apply(tool.getQualityIndex());
                Double oldQuality = authored == null ? 0.0 : Math.max(0.0, authored.getQualityValue());
                Double oldLevel = Math.max(0.0, tool.getItemLevel());
                Double oldDurability = Math.max(0.0, Math.min(100.0,
                        (held.getDurability() / held.getMaxDurability()) * 100.0));

                assertEquals(oldQuality, ItemReadings.quality(held.getItem(), TENS), "quality at index " + index);
                assertEquals(oldQuality, ItemReadings.quality(held, TENS),
                        "a stack whose item has not moved carries the same index, so item_quality agrees");
                assertEquals(oldLevel, ItemReadings.itemLevel(held), "item level " + level);
                assertEquals(oldDurability, ItemReadings.durabilityPercent(held));
                assertEquals(50.0, ItemReadings.durabilityPercent(held));
            }
        }
    }

    @Test
    void theHeldItemReadsDelegateToTheOneReader() {
        Item sword = item("Test_Sword", 9, 1);
        ItemStack held = stack(sword, 30, 60);

        assertEquals(ItemReadings.itemLevel(sword), HeldItemUtil.itemLevelOf(sword));
        assertEquals(ItemReadings.durabilityPercent(held), HeldItemUtil.durabilityPercentOf(held));
        assertEquals(ItemReadings.quality(sword), HeldItemUtil.qualityValueOf(sword));
    }

    @Test
    void theStatTotalSumsTheAdditiveAmountsOfAllThreeBlocks() {
        int health = 5;
        Item piece = item("Test_Piece", 10, 1,
                stats(health, additive(3f), multiplicative(2f)),
                stats(health, additive(4f)),
                stats(health, additive(1.5f)));

        assertEquals(8.5, ItemReadings.statTotal(piece, health), 1e-9,
                "armor 3 + weapon 4 + utility 1.5; the multiplier adds nothing");
    }

    @Test
    void aChannelTheItemAuthorsNothingTowardReadsZero() {
        Item piece = item("Test_Piece", 10, 1, stats(5, additive(3f)), null, null);

        assertEquals(0.0, ItemReadings.statTotal(piece, 6), "a real item carrying nothing is a real 0");
        assertEquals(0.0, ItemReadings.statTotal(item("Test_Bare", 1, 1), 5),
                "an item with no stat blocks at all is a 0 too");
    }

    @Test
    void aStatNoChannelIsRegisteredUnderReadsNothing() {
        Item piece = item("Test_Piece", 10, 1, stats(5, additive(3f)), null, null);

        assertNull(ItemReadings.statTotal(stack(piece, 1, 1), "Test_Unregistered_Channel"),
                "an unregistered channel cannot be answered, which is not the same as 0");
        assertNull(ItemReadings.statTotal(piece, "  "));
    }
}
