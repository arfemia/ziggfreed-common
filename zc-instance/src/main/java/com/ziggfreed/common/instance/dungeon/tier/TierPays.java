package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootPool;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.LootablePreview;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.kit.PaySlot;
import com.ziggfreed.common.ui.kit.Picture;

/**
 * What a tier's clear pays, as pictures for its card (zc-presentation's {@link PaySlot}s, which the kit's
 * {@code TierCardPainter} draws): read from the dungeon file's {@code Rewards.Lootable} table as the server resolves
 * it ({@link LootableConfig#resolve}, so a table another mod contributes rolls to shows them, and a mod-gated file this
 * server refuses shows nothing). The kit only draws pictures; this is where a table's rolls become them, so nothing in
 * the kit's {@code ui/} reads instance or loot content.
 *
 * <p>In the table's order, each picture once: an item a roll's grants hand over is its own picture; a reward a roll
 * hands over (another mod's, a lamp or a boost) is the picture its reward chip draws (the same seam every reward line
 * reads, {@link RewardChips#chipFor}), and none when its chip has none; a console line that is a give is its item; a
 * native drop list ({@code Grants.DropLists}) is one slot of up to {@link PaySlot#MAX_CHOICES} of the items it can
 * hand over (a gem that comes as one of four), or one picture when it can hand over only one; a ladder's floors read
 * as the roll's grants do; and the table's pool, whose entries compete, is one slot of its entries' pictures.
 *
 * <p>Each pay says whether it comes only sometimes ({@link Pay#sometimes}): a roll whose odds this server cannot call
 * always ({@link LootablePreview#alwaysPays}: a chance under 100, a condition), a roll carrying a table gate asked of
 * the player, a ladder none of whose floors is reached at 0, and a pool whose picks a formula decides. A roll that can
 * never fire, and one that keeps itself out of a table's preview ({@code HideInPreview}), show nothing. A card shows
 * the first four (the kit's {@code TierCardPainter.PAYS_SHOWN}); its tooltip, the page's words, names them all.
 */
public final class TierPays {

    /** One thing a clear pays: the slot its card draws, and whether it comes only sometimes. */
    public record Pay(@Nonnull PaySlot slot, boolean sometimes) {
    }

    private TierPays() {
    }

    /**
     * What every clear of {@code dungeon} pays: its {@code Rewards.Lootable} table, resolved; empty when it names none,
     * or none this server answers. On any thread; it asks no player.
     */
    @Nonnull
    public static List<Pay> of(@Nonnull DungeonAsset dungeon) {
        String tableId = dungeon.rewardsLootable();
        LootableAsset table = tableId == null ? null : LootableConfig.getInstance().resolve(tableId);
        return table == null ? List.of() : of(table, TierPays::dropListItems, TierPays::rewardPicture);
    }

    /** The slots of {@code pays}, in order, as a card takes them. */
    @Nonnull
    public static List<PaySlot> slots(@Nonnull List<Pay> pays) {
        List<PaySlot> out = new ArrayList<>(pays.size());
        for (Pay pay : pays) {
            out.add(pay.slot());
        }
        return out;
    }

    /**
     * {@link #of(DungeonAsset)} over a resolved {@code table}, a drop list's items ({@code dropLists}, its item ids in
     * order) and a reward's picture ({@code rewards}) read through the given functions.
     */
    @Nonnull
    static List<Pay> of(@Nonnull LootableAsset table, @Nonnull Function<String, List<String>> dropLists,
            @Nonnull Function<RewardSpec, Picture> rewards) {
        Collector out = new Collector(dropLists, rewards);
        for (Roll roll : table.rollsOrEmpty()) {
            if (roll == null || roll.hidesInPreview() || LootablePreview.neverPays(roll)) {
                continue;
            }
            boolean sometimes = !LootablePreview.alwaysPays(roll) || roll.gates().length > 0;
            out.add(roll.getGrants(), sometimes);
            Roll.Ladder ladder = roll.getLadder();
            Roll.Ladder.Floor[] floors = ladder == null ? null : ladder.getFloors();
            if (floors != null) {
                boolean reached = reachedAtZero(floors);
                for (Roll.Ladder.Floor floor : floors) {
                    if (floor != null) {
                        out.add(floor.getGrants(), sometimes || !reached);
                    }
                }
            }
        }
        LootPool pool = table.getPool();
        if (pool != null && pool.getEntries() != null) {
            List<Picture> choices = new ArrayList<>();
            for (LootPool.Entry entry : pool.getEntries()) {
                if (entry != null) {
                    choices.addAll(out.pictures(entry.getGrants()));
                }
            }
            out.choice(choices, pool.getRolls() != null);
        }
        return out.pays;
    }

    /** Whether a ladder pays some floor whatever its sum: a floor at or under 0 is always reached. */
    private static boolean reachedAtZero(@Nonnull Roll.Ladder.Floor[] floors) {
        for (Roll.Ladder.Floor floor : floors) {
            if (floor != null && floor.effectiveMin() <= 0.0) {
                return true;
            }
        }
        return false;
    }

    /** The items a native drop list can hand over, each once, in its own order; empty for a list nothing defines. */
    @Nonnull
    static List<String> dropListItems(@Nonnull String dropListId) {
        try {
            ItemDropList list = ItemDropList.getAssetMap().getAsset(dropListId);
            if (list == null || list.getContainer() == null) {
                return List.of();
            }
            Set<String> items = new LinkedHashSet<>();
            for (ItemDrop drop : list.getContainer().getAllDrops(new ArrayList<>())) {
                if (drop != null && drop.getItemId() != null && !drop.getItemId().isBlank()) {
                    items.add(drop.getItemId());
                }
            }
            return List.copyOf(items);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** A reward's picture: the one its reward chip draws, or none. */
    @Nonnull
    static Picture rewardPicture(@Nonnull RewardSpec spec) {
        try {
            RewardChip chip = RewardChips.chipFor(spec);
            return chip != null && chip.hasIcon() ? Picture.item(chip.iconItemId()) : Picture.NONE;
        } catch (Throwable t) {
            return Picture.NONE;
        }
    }

    /** The pays being read: each picture once, the first time it comes. */
    private static final class Collector {

        private final Function<String, List<String>> dropLists;
        private final Function<RewardSpec, Picture> rewards;
        private final List<Pay> pays = new ArrayList<>();
        private final Set<Picture> seen = new LinkedHashSet<>();

        Collector(@Nonnull Function<String, List<String>> dropLists, @Nonnull Function<RewardSpec, Picture> rewards) {
            this.dropLists = dropLists;
            this.rewards = rewards;
        }

        /** Every slot {@code grants} hands over: an item, a reward or a give one picture each, a drop list one slot. */
        void add(@Nullable LootGrants grants, boolean sometimes) {
            if (grants == null) {
                return;
            }
            for (Picture picture : pictures(grants)) {
                one(picture, sometimes);
            }
            String[] lists = grants.getDropLists();
            if (lists != null) {
                for (String list : lists) {
                    if (list != null && !list.isBlank()) {
                        List<Picture> choices = new ArrayList<>();
                        for (String item : dropLists.apply(list.trim())) {
                            choices.add(Picture.item(item));
                        }
                        choice(choices, sometimes);
                    }
                }
            }
        }

        /** The single pictures {@code grants} hands over: its items, its rewards' pictures, its gives. */
        @Nonnull
        List<Picture> pictures(@Nullable LootGrants grants) {
            List<Picture> out = new ArrayList<>();
            if (grants == null) {
                return out;
            }
            for (LootGrants.Item item : grants.itemsOrEmpty()) {
                if (item != null && item.getItem() != null && !item.getItem().isBlank()) {
                    out.add(Picture.item(item.getItem().trim()));
                }
            }
            for (RewardSpec reward : grants.rewardSpecs()) {
                Picture picture = rewards.apply(reward);
                if (picture != null && !picture.isEmpty()) {
                    out.add(picture);
                }
            }
            String[] commands = grants.getCommands();
            if (commands != null) {
                for (String line : commands) {
                    CommandRunner.Give give = line == null || line.isBlank() ? null : CommandRunner.readGive(line);
                    if (give != null) {
                        out.add(Picture.item(give.itemId()));
                    }
                }
            }
            return out;
        }

        /** One picture, once. */
        private void one(@Nonnull Picture picture, boolean sometimes) {
            if (seen.add(picture)) {
                pays.add(new Pay(PaySlot.of(picture), sometimes));
            }
        }

        /** A slot of one of {@code choices}: a single one reads as its picture, none adds nothing. */
        void choice(@Nonnull List<Picture> choices, boolean sometimes) {
            List<Picture> fresh = new ArrayList<>(new LinkedHashSet<>(choices));
            if (fresh.isEmpty()) {
                return;
            }
            if (fresh.size() == 1) {
                one(fresh.get(0), sometimes);
                return;
            }
            if (seen.addAll(fresh)) {
                pays.add(new Pay(PaySlot.oneOf(fresh), sometimes));
            }
        }
    }
}
