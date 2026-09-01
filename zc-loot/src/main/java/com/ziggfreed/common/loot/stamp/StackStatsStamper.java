package com.ziggfreed.common.loot.stamp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.i18n.Msg;
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
 * <p><b>Durability is the one id that never reaches the record.</b> It is raised on the STACK
 * instead, because durability is a property of the item rather than a stat channel, so there is
 * nothing for an equip bridge to put a modifier on. The roll math never learns the difference - a
 * pool rolls it against the same budget as everything else - and only the write knows.
 *
 * <p>What a stamped stat is CALLED is not decided here either, and for a sharper reason: a stat id
 * belongs to the mod that invented it, so only that mod can say what it means, in what colour, in a
 * player's own language. It says so through {@link StatNamer}, which the tooltip consults per line
 * and {@link #describe} answers from. With none
 * registered the write is unchanged and complete - the stats are simply reported plainly.
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
        double durability = 0;
        Map<String, Double> stats = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : added.entrySet()) {
            if (DefaultStatNames.isDurability(entry.getKey())) {
                durability += entry.getValue();
            } else {
                stats.put(entry.getKey(), entry.getValue());
            }
        }

        ItemStack result = stack;
        if (durability > 0) {
            result = result.withMaxDurability(result.getMaxDurability() + durability)
                    .withIncreasedDurability(durability);
        }
        if (stats.isEmpty()) {
            // A durability-only stamp is still a stamp: it spent the budget, and the repeat cost has
            // to see it, so the count advances even though the record gained no entry.
            Map<String, Double> carried = StackStats.entriesOf(result);
            return StackStats.stampReplacingWithCount(result,
                    carried != null ? carried : Map.of(), StackStats.stampCountOf(result) + 1);
        }
        Map<String, Double> merged = StackStats.mergeWith(result, stats);
        ItemStack stamped = StackStats.stampReplacingWithCount(result, merged,
                StackStats.stampCountOf(result) + 1);
        // The numbers and the way they READ are written in the SAME operation, so a stamped item can
        // never carry stats its tooltip does not show. The merged map goes in, not the added one:
        // the tooltip states the item's whole current condition, not this one stamp's delta.
        return StampTooltip.apply(stamped, merged, null);
    }

    /**
     * The stats, then the authored identity. Identity is applied LAST so a rename lands on the stack
     * that already carries its tooltip, and it is applied at all only where a file asked for it.
     */
    @Override
    @Nonnull
    public ItemStack apply(@Nonnull ItemStack stack, @Nonnull List<StatRoll> entries,
            @Nullable StampIdentity identity) {
        ItemStack stamped = apply(stack, entries);
        if (identity == null || identity.isEmpty()) {
            return stamped;
        }
        return applyIdentity(stamped, identity);
    }

    /**
     * The rename and the rarity, each independent and each skipped when unauthored.
     *
     * <p>The quality id is resolved to its index HERE rather than stored anywhere: the index is
     * registration order, so it is only meaningful against the asset map as it stands right now.
     */
    @Nonnull
    private static ItemStack applyIdentity(@Nonnull ItemStack stack, @Nonnull StampIdentity identity) {
        ItemStack result = stack;
        String qualityId = identity.qualityId();
        if (qualityId != null && !qualityId.isBlank()) {
            try {
                result = result.withQuality(ItemQuality.getAssetMap()
                        .getIndexOrDefault(qualityId, ItemQuality.DEFAULT_INDEX));
            } catch (Throwable ignored) {
                // An unknown quality id costs the tint, never the stamp.
            }
        }
        String nameKey = identity.nameKey();
        if (nameKey != null && !nameKey.isBlank()) {
            try {
                Message renamed = Msg.keyNamed(nameKey,
                        Map.of("item", result.getItem().getTranslationMessage()));
                result = StampTooltip.apply(result, orEmpty(StackStats.entriesOf(result)), renamed);
            } catch (Throwable ignored) {
                // A bad rename key costs the name, never the stats already written.
            }
        }
        return result;
    }

    /** A stored entries map that is never null, for the identity rewrite. */
    @Nonnull
    private static Map<String, Double> orEmpty(@Nullable Map<String, Double> entries) {
        return entries != null ? entries : Map.of();
    }

    /**
     * Answered through {@link StatNamerRegistry}, so the line a reporting surface prints is the same
     * one the item's own tooltip shows. Never null: an id no vocabulary claims still reads as itself
     * with its points, which is more use to whoever has to debug it than a blank.
     */
    @Override
    @Nonnull
    public Message describe(@Nonnull StatRoll entry) {
        return StatNamerRegistry.name(entry.statId(), entry.points());
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
