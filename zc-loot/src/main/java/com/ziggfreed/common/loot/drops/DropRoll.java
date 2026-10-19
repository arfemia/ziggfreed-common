package com.ziggfreed.common.loot.drops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootPool;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.DropRows;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.util.SafeLog;

/**
 * A table's roll inside a native drop list, and its preview for the engine's own check.
 *
 * <p>The roll is the loot core's own decision ({@link LootEngine#select}: the table's rolls then its pool, its
 * {@code Requires} asked first) against a lookup about the player the drop is for
 * ({@link LootRewardKinds#lookupAbout}), drawn from the engine's random source, and each payout is then dropped as
 * {@link DropRows} reads it: an item into the engine's drops, a native drop list rolled into the same drops, a nested
 * table rolled the same way, and anything else not at all. A roll's {@code Cue} plays to that player when what it
 * rode on dropped, or rode on nothing, as the loot core's cue rule reads; with nobody known it plays to no one.
 *
 * <p>Tables nest at most {@link #MAX_DEPTH} deep, a nested native list's own ZigLootable included, since a chain that
 * long is a loop; past it the roll drops nothing and says so once.
 */
final class DropRoll {

    /** How deep tables rolled inside tables may nest, as a {@code Lootable} reward's may. */
    static final int MAX_DEPTH = 4;

    /** What a drop's cues and lines are labelled with, before the table's id. */
    static final String SOURCE_PREFIX = "drops:";

    private static final Set<String> WARNED_DEEP = ConcurrentHashMap.newKeySet();

    /** What the engine's preview walk on this thread has visited, while one runs. */
    private static final ThreadLocal<Set<String>> WALKING = new ThreadLocal<>();

    private DropRoll() {
    }

    /** Roll {@code tableId} into {@code drops} for {@code who} (null for nobody), for {@code trigger} (null: all). */
    static void roll(@Nonnull List<ItemDrop> drops, @Nonnull DoubleSupplier sample, @Nonnull String tableId,
            @Nullable String trigger, @Nullable DropContext.Who who) {
        if (DropContext.depth() >= MAX_DEPTH) {
            if (WARNED_DEEP.add(tableId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn("[loot] drops: the loot table '" + tableId + "' sits " + MAX_DEPTH + " tables deep "
                        + "inside a drop list, which is almost certainly a loop, so it drops nothing there");
            }
            return;
        }
        DropContext.within(who, () -> rollInside(drops, sample, tableId, trigger, who));
    }

    private static void rollInside(@Nonnull List<ItemDrop> drops, @Nonnull DoubleSupplier sample,
            @Nonnull String tableId, @Nullable String trigger, @Nullable DropContext.Who who) {
        try {
            LootEngine.Resolved table = LootEngine.resolve(LootRef.of(new String[] {tableId}, null), null);
            FactorLookup lookup = LootRewardKinds.lookupAbout(
                    who == null ? FactorContext.about(null, null) : who.context());
            Into into = new Into(drops, sample, who);
            List<String> cues = new ArrayList<>();
            for (LootEngine.Selected picked : LootEngine.select(table.rolls(), table.pools(), trigger, lookup,
                    sample)) {
                int before = drops.size();
                DropRows.walk(picked.grants(), into);
                String cue = picked.cue();
                LootGrants grants = picked.grants();
                if (cue != null && (grants == null || grants.isEmpty() || drops.size() > before)) {
                    cues.add(cue);
                }
            }
            if (who != null && !cues.isEmpty()) {
                LootCues.presentAll(cues, who.subject(), SOURCE_PREFIX + tableId);
            }
        } catch (Throwable t) {
            SafeLog.warn("[loot] drops: the loot table '" + tableId + "' could not roll inside a drop list", t);
        }
    }

    /** Where one roll's payouts drop: the engine's own list, the same random source, the same player. */
    private record Into(@Nonnull List<ItemDrop> drops, @Nonnull DoubleSupplier sample,
            @Nullable DropContext.Who who) implements DropRows.Sink {

        @Override
        public void item(@Nonnull String itemId, int min, int max) {
            drops.add(new ItemDrop(itemId, null, min, max));
        }

        @Override
        public void dropList(@Nonnull String dropListId, int rolls) {
            ItemDropList list = ItemDropList.getAssetMap().getAsset(dropListId);
            ItemDropContainer container = list == null ? null : list.getContainer();
            if (container == null) {
                return;
            }
            for (int i = 0; i < rolls; i++) {
                container.populateDrops(drops, sample, dropListId);
            }
        }

        @Override
        public void table(@Nonnull String tableId, @Nullable String trigger) {
            roll(drops, sample, tableId, trigger, who);
        }

        @Override
        public void unpaid(@Nonnull String what) {
            // Pays nothing in a drop list; the audit names it once the stores load (DropAudit).
        }
    }

    // ==================== the engine's preview ====================

    /**
     * Every item {@code tableId} could drop, into {@code list}: its items, each named drop list's own, and each nested
     * table's, through every roll, ladder floor and pool entry. When that names nothing (no table, a table not loaded
     * yet, one that drops no item) one entry naming no item stands for the table, so the engine's check that a drop
     * list can drop something never refuses a whole list over it.
     */
    static void everyDrop(@Nonnull List<ItemDrop> list, @Nullable String tableId) {
        int before = list.size();
        // A table's native list may hold a ZigLootable naming a table already walked: one walk per thread shares one
        // set of what it has visited, so a loop ends instead of recursing, and only the outermost walk stands in.
        Set<String> outer = WALKING.get();
        Set<String> seen = outer == null ? new HashSet<>() : outer;
        if (outer == null) {
            WALKING.set(seen);
        }
        try {
            if (tableId != null) {
                everyDrop(list, tableId, seen);
            }
        } catch (Throwable t) {
            // The table's store may still be loading beside the drop lists: the stand-in below answers for it.
        } finally {
            if (outer == null) {
                WALKING.remove();
            }
        }
        if (outer == null && list.size() == before) {
            list.add(new ItemDrop(null, null, 0, 0));
        }
    }

    private static void everyDrop(@Nonnull List<ItemDrop> list, @Nonnull String tableId, @Nonnull Set<String> seen) {
        if (!seen.add("table:" + tableId.toLowerCase(Locale.ROOT))) {
            return;
        }
        LootableAsset table = LootableConfig.getInstance().resolve(tableId);
        if (table == null) {
            return;
        }
        DropRows.Sink sink = new DropRows.Sink() {
            @Override
            public void item(@Nonnull String itemId, int min, int max) {
                list.add(new ItemDrop(itemId, null, min, max));
            }

            @Override
            public void dropList(@Nonnull String dropListId, int rolls) {
                if (!seen.add("list:" + dropListId.toLowerCase(Locale.ROOT))) {
                    return;
                }
                ItemDropList named = ItemDropList.getAssetMap().getAsset(dropListId);
                if (named != null && named.getContainer() != null) {
                    named.getContainer().getAllDrops(list);
                }
            }

            @Override
            public void table(@Nonnull String nested, @Nullable String trigger) {
                everyDrop(list, nested, seen);
            }

            @Override
            public void unpaid(@Nonnull String what) {
                // Drops nothing.
            }
        };
        forEachGrants(table, grants -> DropRows.walk(grants, sink));
    }

    /** Every grants group {@code table} can pay, as resolved: each roll's own and its ladder floors', then its pool's. */
    static void forEachGrants(@Nonnull LootableAsset table, @Nonnull Consumer<LootGrants> visit) {
        for (Roll roll : table.rollsOrEmpty()) {
            if (roll == null) {
                continue;
            }
            visit.accept(roll.getGrants());
            Roll.Ladder ladder = roll.getLadder();
            Roll.Ladder.Floor[] floors = ladder == null ? null : ladder.getFloors();
            if (floors != null) {
                for (Roll.Ladder.Floor floor : floors) {
                    if (floor != null) {
                        visit.accept(floor.getGrants());
                    }
                }
            }
        }
        LootPool pool = table.getPool();
        LootPool.Entry[] entries = pool == null ? null : pool.getEntries();
        if (entries != null) {
            for (LootPool.Entry entry : entries) {
                if (entry != null) {
                    visit.accept(entry.getGrants());
                }
            }
        }
    }
}
