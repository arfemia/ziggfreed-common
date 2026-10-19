package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.LootGrants;

/**
 * What one grants group pays inside a native drop list, where a table rolled by a {@code ZigLootable} drop container
 * hands nothing to anyone: the engine spawns what the list produced, where it drops its own loot.
 *
 * <p>Only what can drop maps. An item (an {@code Items} entry, an {@code Item} reward) drops as itself; a native drop
 * list (a {@code DropLists} entry, a {@code Droplist} reward with its {@code Rolls}) rolls into the same drops; another
 * table (a {@code Lootable} reward with its {@code Trigger}) rolls the same way. Everything else pays nothing there and
 * is named to {@link Sink#unpaid}: {@code Commands}, and every other reward kind (a currency, a level, an effect, a
 * stamped item, a Claim), since a drop list has no receiver to pay and a reward is collected, never handed over
 * (M428). The rows are read through this kind vocabulary's own readers ({@link LootRewardKinds#itemIdOf},
 * {@code countOf}, {@code tableIdOf}, {@link DroplistRewardKind}'s list and rolls), so a row reads here as it pays
 * everywhere else.
 */
public final class DropRows {

    /** What {@link Sink#unpaid} names for a group's {@code Commands}. */
    public static final String COMMANDS = "Commands";

    private DropRows() {
    }

    /** Where {@link #walk} sends each row of a group, by what it drops as. */
    public interface Sink {

        /** {@code itemId}, between {@code min} and {@code max} of it (equal for a quantity already drawn). */
        void item(@Nonnull String itemId, int min, int max);

        /** The native drop list {@code dropListId}, rolled {@code rolls} times into the same drops. */
        void dropList(@Nonnull String dropListId, int rolls);

        /** The loot table {@code tableId}, rolled into the same drops for its {@code trigger} (null for every roll). */
        void table(@Nonnull String tableId, @Nullable String trigger);

        /** A row that drops nothing: {@link #COMMANDS}, or the reward kind's id as written. */
        void unpaid(@Nonnull String what);
    }

    /** Send every row of {@code grants} to {@code sink}, in authored order: items, drop lists, commands, rewards. */
    public static void walk(@Nullable LootGrants grants, @Nonnull Sink sink) {
        if (grants == null) {
            return;
        }
        for (LootGrants.Item item : grants.itemsOrEmpty()) {
            String itemId = item.getItem();
            if (itemId != null) {
                sink.item(itemId.trim(), item.effectiveCount(), item.effectiveCountMax());
            }
        }
        String[] dropLists = grants.getDropLists();
        if (dropLists != null) {
            for (String dropListId : dropLists) {
                if (dropListId != null && !dropListId.isBlank()) {
                    sink.dropList(dropListId.trim(), 1);
                }
            }
        }
        String[] commands = grants.getCommands();
        if (commands != null) {
            for (String command : commands) {
                if (command != null && !command.isBlank()) {
                    sink.unpaid(COMMANDS);
                }
            }
        }
        for (RewardSpec spec : grants.rewardSpecs()) {
            walk(spec, sink);
        }
    }

    /** Send one reward row to {@code sink}: an item, a drop list or a table it drops as, else what it is. */
    public static void walk(@Nonnull RewardSpec spec, @Nonnull Sink sink) {
        String kind = spec.kind();
        if (LootRewardKinds.KIND_ITEM.equalsIgnoreCase(kind)) {
            String itemId = LootRewardKinds.itemIdOf(spec);
            int count = LootRewardKinds.countOf(spec);
            if (itemId != null && count > 0) {
                sink.item(itemId, count, count);
            }
            return;
        }
        if (DroplistRewardKind.KIND.equalsIgnoreCase(kind)) {
            String dropListId = DroplistRewardKind.droplistIdOf(spec);
            if (dropListId != null) {
                sink.dropList(dropListId, DroplistRewardKind.rollsOf(spec));
            }
            return;
        }
        if (LootRewardKinds.KIND_LOOTABLE.equalsIgnoreCase(kind)) {
            String tableId = LootRewardKinds.tableIdOf(spec);
            if (!tableId.isEmpty()) {
                sink.table(tableId, spec.param("trigger"));
            }
            return;
        }
        sink.unpaid(kind);
    }
}
