package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
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
 * {@code TierCardPainter} draws) and as words for its tooltip ({@link #line}): read from the dungeon file's
 * {@code Rewards.Lootable} table as the server resolves it ({@link LootableConfig#resolve}, so a table another mod
 * contributes rolls to shows them, and a mod-gated file this server refuses shows nothing). The kit only draws
 * pictures; this is where a table's rolls become them, so nothing in the kit's {@code ui/} reads instance or loot
 * content.
 *
 * <p>In the table's order, each picture once: an item a roll's grants hand over is its own picture, named as the item;
 * a reward a roll hands over (another mod's, a lamp or a boost) is the picture its reward chip draws (the same seam
 * every reward line reads, {@link RewardChips#chipFor}), and none when its chip has none, named as the chip; a console
 * line that is a give is its item; a native drop list ({@code Grants.DropLists}) is one slot of up to
 * {@link PaySlot#MAX_CHOICES} of the items it can hand over (a gem that comes as one of four), or one picture when it
 * can hand over only one, named as one of its items; a ladder's floors read as the roll's grants do; and the table's
 * pool, whose entries compete, is one slot of its entries' pictures. A roll that writes {@code NameKey} names the first
 * pay it adds as those words ("a gem"), in place of its grants' names, as a table's preview reads it.
 *
 * <p>Each pay says whether it comes only sometimes ({@link Pay#sometimes}): a roll whose odds this server cannot call
 * always ({@link LootablePreview#alwaysPays}: a chance under 100, a condition), a roll carrying a table gate asked of
 * the player, a ladder none of whose floors is reached at 0, and a pool whose picks a formula decides. A roll that can
 * never fire, and one that keeps itself out of a table's preview ({@code HideInPreview}), show nothing. A card shows
 * the first four (the kit's {@code TierCardPainter.PAYS_SHOWN}); its tooltip names them all.
 */
public final class TierPays {

    private static final String PREFIX = "ziggfreedcommon.dungeon.";

    /**
     * One thing a clear pays: the slot its card draws, whether it comes only sometimes, and what it reads as in words
     * (null when nothing names it, and then the tooltip leaves it out).
     */
    public record Pay(@Nonnull PaySlot slot, boolean sometimes, @Nullable Message words) {

        /** A pay with no words of its own. */
        public Pay(@Nonnull PaySlot slot, boolean sometimes) {
            this(slot, sometimes, null);
        }
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
        return table == null ? List.of()
                : of(table, TierPays::dropListItems, TierPays::rewardPicture, TierPays::itemName,
                        TierPays::rewardName, ContentKeys::tr);
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
     * What {@code pays} come to in words, as a card's tooltip reads it: "Pays: Thorium Ingot, Void Crystal Shards,
     * sometimes a gem", each pay that has words in order, one that comes only sometimes after the word for it; null
     * when none has words.
     */
    @Nullable
    public static Message line(@Nonnull List<Pay> pays) {
        Message list = null;
        for (Pay pay : pays) {
            if (pay.words() == null) {
                continue;
            }
            Message words = pay.sometimes() ? Msg.key(PREFIX + "gate.pays.sometimes", pay.words()) : pay.words();
            list = list == null ? words : Msg.key(PREFIX + "gate.list", list, words);
        }
        return list == null ? null : Msg.key(PREFIX + "gate.pays.line", list);
    }

    /**
     * The note under a card's pictures, of the first {@code shown} pays: "Sometimes a gem", the first that comes only
     * sometimes and has words; null when none does.
     */
    @Nullable
    public static Message note(@Nonnull List<Pay> pays, int shown) {
        for (int i = 0; i < pays.size() && i < shown; i++) {
            Pay pay = pays.get(i);
            if (pay.sometimes() && pay.words() != null) {
                return Msg.key(PREFIX + "gate.pays.note", pay.words());
            }
        }
        return null;
    }

    /**
     * {@link #of(DungeonAsset)} over a resolved {@code table}, a drop list's items ({@code dropLists}, its item ids in
     * order), a reward's picture ({@code rewards}), an item's name ({@code itemNames}), a reward's name
     * ({@code rewardNames}, null for none) and a roll's {@code NameKey} line ({@code keys}), read through the given
     * functions.
     */
    @Nonnull
    static List<Pay> of(@Nonnull LootableAsset table, @Nonnull Function<String, List<String>> dropLists,
            @Nonnull Function<RewardSpec, Picture> rewards, @Nonnull Function<String, Message> itemNames,
            @Nonnull Function<RewardSpec, Message> rewardNames, @Nonnull Function<String, Message> keys) {
        Collector out = new Collector(dropLists, rewards, itemNames, rewardNames);
        for (Roll roll : table.rollsOrEmpty()) {
            if (roll == null || roll.hidesInPreview() || LootablePreview.neverPays(roll)) {
                continue;
            }
            boolean sometimes = !LootablePreview.alwaysPays(roll) || roll.gates().length > 0;
            out.named = roll.getNameKey() == null ? null : keys.apply(roll.getNameKey());
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
            out.named = null;
        }
        LootPool pool = table.getPool();
        if (pool != null && pool.getEntries() != null) {
            List<Named> choices = new ArrayList<>();
            for (LootPool.Entry entry : pool.getEntries()) {
                if (entry != null) {
                    choices.addAll(out.named(entry.getGrants()));
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

    /** A reward's name: the line its reward chip reads, or null. */
    @Nullable
    static Message rewardName(@Nonnull RewardSpec spec) {
        try {
            RewardChip chip = RewardChips.chipFor(spec);
            return chip == null ? null : chip.label();
        } catch (Throwable t) {
            return null;
        }
    }

    /** An item's name as the player's client writes it, or null when it cannot be read. */
    @Nullable
    static Message itemName(@Nonnull String itemId) {
        try {
            return NativeNames.itemNameMsg(itemId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A picture and what it reads as in words (null for none). */
    record Named(@Nonnull Picture picture, @Nullable Message words) {
    }

    /** The pays being read: each picture once, the first time it comes. */
    private static final class Collector {

        private final Function<String, List<String>> dropLists;
        private final Function<RewardSpec, Picture> rewards;
        private final Function<String, Message> itemNames;
        private final Function<RewardSpec, Message> rewardNames;
        private final List<Pay> pays = new ArrayList<>();
        private final Set<Picture> seen = new LinkedHashSet<>();
        /** The words of the roll being read ({@code NameKey}), given to the first pay it adds; null for none. */
        @Nullable Message named;

        Collector(@Nonnull Function<String, List<String>> dropLists, @Nonnull Function<RewardSpec, Picture> rewards,
                @Nonnull Function<String, Message> itemNames, @Nonnull Function<RewardSpec, Message> rewardNames) {
            this.dropLists = dropLists;
            this.rewards = rewards;
            this.itemNames = itemNames;
            this.rewardNames = rewardNames;
        }

        /** Every slot {@code grants} hands over: an item, a reward or a give one picture each, a drop list one slot. */
        void add(@Nullable LootGrants grants, boolean sometimes) {
            if (grants == null) {
                return;
            }
            for (Named one : named(grants)) {
                one(one, sometimes);
            }
            String[] lists = grants.getDropLists();
            if (lists != null) {
                for (String list : lists) {
                    if (list != null && !list.isBlank()) {
                        List<Named> choices = new ArrayList<>();
                        for (String item : dropLists.apply(list.trim())) {
                            choices.add(new Named(Picture.item(item), itemNames.apply(item)));
                        }
                        choice(choices, sometimes);
                    }
                }
            }
        }

        /** The single pictures {@code grants} hands over, each with its name: its items, its rewards, its gives. */
        @Nonnull
        List<Named> named(@Nullable LootGrants grants) {
            List<Named> out = new ArrayList<>();
            if (grants == null) {
                return out;
            }
            for (LootGrants.Item item : grants.itemsOrEmpty()) {
                if (item != null && item.getItem() != null && !item.getItem().isBlank()) {
                    String id = item.getItem().trim();
                    out.add(new Named(Picture.item(id), itemNames.apply(id)));
                }
            }
            for (RewardSpec reward : grants.rewardSpecs()) {
                Picture picture = rewards.apply(reward);
                if (picture != null && !picture.isEmpty()) {
                    out.add(new Named(picture, rewardNames.apply(reward)));
                }
            }
            String[] commands = grants.getCommands();
            if (commands != null) {
                for (String line : commands) {
                    CommandRunner.Give give = line == null || line.isBlank() ? null : CommandRunner.readGive(line);
                    if (give != null) {
                        out.add(new Named(Picture.item(give.itemId()), itemNames.apply(give.itemId())));
                    }
                }
            }
            return out;
        }

        /** One picture, once. */
        private void one(@Nonnull Named one, boolean sometimes) {
            if (seen.add(one.picture())) {
                pays.add(new Pay(PaySlot.of(one.picture()), sometimes, words(one.words())));
            }
        }

        /**
         * A slot of one of {@code choices}: a single one reads as its picture, none adds nothing; several read as one of
         * their names.
         */
        void choice(@Nonnull List<Named> choices, boolean sometimes) {
            Map<Picture, Message> fresh = new LinkedHashMap<>();
            for (Named choice : choices) {
                fresh.putIfAbsent(choice.picture(), choice.words());
            }
            if (fresh.isEmpty()) {
                return;
            }
            if (fresh.size() == 1) {
                Map.Entry<Picture, Message> only = fresh.entrySet().iterator().next();
                one(new Named(only.getKey(), only.getValue()), sometimes);
                return;
            }
            List<Picture> pictures = new ArrayList<>(fresh.keySet());
            if (seen.addAll(pictures)) {
                pays.add(new Pay(PaySlot.oneOf(pictures), sometimes, words(oneOf(fresh.values()))));
            }
        }

        /** The roll's own words for the first pay it adds, else {@code own}. */
        @Nullable
        private Message words(@Nullable Message own) {
            if (named != null) {
                Message words = named;
                named = null;
                return words;
            }
            return own;
        }

        /** "one of Ruby, Sapphire, Emerald, Topaz": the names that read, or null when none does. */
        @Nullable
        private static Message oneOf(@Nonnull Iterable<Message> names) {
            Message list = null;
            for (Message name : names) {
                if (name != null) {
                    list = list == null ? name : Msg.key(PREFIX + "gate.list", list, name);
                }
            }
            return list == null ? null : Msg.key(PREFIX + "gate.pays.one_of", list);
        }
    }
}
