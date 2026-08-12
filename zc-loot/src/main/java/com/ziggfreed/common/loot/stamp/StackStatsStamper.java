package com.ziggfreed.common.loot.stamp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.stats.StackStats;

/**
 * The default {@link Stamper}: stats live in the item stack's own metadata, so a stamped item IS the
 * record and nothing on the server needs to remember it.
 *
 * <p>The important consequence is that stamps travel with the item. Trade it, chest it, carry it to
 * another world, and the stats come along, because they were never held in a side table keyed by
 * anything that could go stale. It also means a stamped item is never a new item ASSET - the catalog
 * stays the size the pack authors wrote, however many stamped swords exist.
 *
 * <p>What a stamped stat actually DOES to a wearer is not decided here. This class owns the format
 * only; something else reads those entries and turns them into a real effect on equip.
 *
 * <p>Re-stamping SUMS: two points of damage stamped twice reads as four, and the stamp count goes up
 * by one so budget math can see the history.
 */
public final class StackStatsStamper implements Stamper {

    @Override
    @Nonnull
    public StampInspection inspect(@Nonnull ItemStack stack) {
        Map<String, Integer> byStat = wholePoints(StackStats.entriesOf(stack));
        int total = 0;
        for (int points : byStat.values()) {
            total += points;
        }
        return new StampInspection(total, byStat, StackStats.stampCountOf(stack));
    }

    @Override
    @Nonnull
    public ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries) {
        Map<String, Double> added = sum(entries);
        if (added.isEmpty()) {
            return stack;
        }
        Map<String, Double> merged = StackStats.mergeWith(stack, added);
        return StackStats.stampReplacingWithCount(stack, merged, StackStats.stampCountOf(stack) + 1);
    }

    /**
     * A stored entries map rounded to whole points - what budget math is counted in. Pure, so it is
     * testable without an item.
     */
    @Nonnull
    static Map<String, Integer> wholePoints(@Nullable Map<String, Double> entries) {
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : entries.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.merge(entry.getKey(), (int) Math.round(entry.getValue()), Integer::sum);
            }
        }
        return out;
    }

    /**
     * A roll list summed into one {@code statId -> amount} map, same-stat rolls added together.
     * Pure, so it is testable without an item.
     */
    @Nonnull
    static Map<String, Double> sum(@Nonnull List<StatRoll> rolls) {
        if (rolls.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (StatRoll roll : rolls) {
            if (roll != null && roll.statId() != null && !roll.statId().isBlank()) {
                out.merge(roll.statId(), (double) roll.points(), Double::sum);
            }
        }
        return out;
    }
}
