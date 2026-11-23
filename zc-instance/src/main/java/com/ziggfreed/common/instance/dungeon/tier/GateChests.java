package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.claims.RewardChestAsset;
import com.ziggfreed.common.instance.claims.RewardChestConfig;
import com.ziggfreed.common.instance.claims.SpoilsChests;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.ui.kit.ChestSlot;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.util.SafeLog;

/**
 * The reward chests a dungeon's gate shows ({@code Gate.Chests}), as one player stands with each: its picture (the
 * chest file's {@code Look.Prop}, the chest a player finds), its name ({@code Contents.TitleKey}), and whether they
 * took it already, read from the chest file's own {@code Contents.Diminish} count, the one its payouts count under
 * ({@link SpoilsChests.Counting}): a chest whose next share for them is nothing is taken until its window turns, and
 * any other waits. A chest counted apart per difficulty ({@code Contents.PerDifficulty}) reads the count of the
 * gate's own dungeon file's {@code Difficulty}, the one page shown for every rung. Nothing here is counted or paid.
 */
final class GateChests {

    private static final String PREFIX = "ziggfreedcommon.dungeon.";

    /** The picture a chest shows when its prop draws none: the game's own Memories chest. */
    private static final Picture CHEST =
            Picture.texture("UI/Custom/Pages/Memories/MemoriesProgress/IconChestActive.png");

    /** Chest ids a gate named that no loaded file defines, already logged. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * One chest as its row shows it.
     *
     * @param slot  the kit's chest slot (its picture, the gold check once taken, the chest's own lead as its tooltip)
     * @param name  the chest's name
     * @param line  while it waits, the gate's line for it; once taken, when it fills again
     * @param taken whether the player took it in its current window
     */
    record Row(@Nonnull ChestSlot slot, @Nonnull Message name, @Nonnull Message line, boolean taken) {
    }

    private GateChests() {
    }

    /** {@code dungeon}'s chests for {@code player} at {@code nowMs}, in the order its file writes them. */
    @Nonnull
    static List<Row> of(@Nonnull DungeonAsset dungeon, @Nonnull UUID player, long nowMs) {
        List<Row> rows = new ArrayList<>();
        for (DungeonAsset.GateChest entry : dungeon.gateChests()) {
            String id = entry.chest();
            RewardChestAsset file = id == null ? null : RewardChestConfig.getInstance().resolve(id);
            if (file == null) {
                if (id != null && WARNED.add(id.toLowerCase(Locale.ROOT))) {
                    SafeLog.warn("[dungeon] the gate of '" + dungeon.getId() + "' shows the reward chest '" + id
                            + "' (Gate.Chests), which no loaded file defines, so its page leaves it out");
                }
                continue;
            }
            DiminishSpec spec = file.diminish();
            String key = SpoilsChests.Counting.of(SpoilsChests.sourceOf(id), file.contentsPerDifficulty(),
                    dungeon.difficulty(), false, null).diminishKey();
            boolean taken = spec != null && ClaimStore.shared().nextShare(player, spec, key, nowMs) <= 0.0;
            Message lead = ContentKeys.tr(file.leadKey());
            ChestSlot slot = new ChestSlot(id, Picture.item(file.prop()).or(CHEST), taken, lead);
            Message line = taken ? taken(spec)
                    : entry.waitingKey() != null ? ContentKeys.tr(entry.waitingKey()) : lead;
            rows.add(new Row(slot, name(file), line, taken));
        }
        return rows;
    }

    /** The chest's name: its Claim's title, else its banner's, else the library's word for a reward chest. */
    @Nonnull
    private static Message name(@Nonnull RewardChestAsset file) {
        String key = file.contentsTitleKey() != null ? file.contentsTitleKey() : file.bannerTitleKey();
        return key == null ? Msg.key(PREFIX + "gate.chest.name") : ContentKeys.tr(key);
    }

    /** Once taken: when it fills again, by its window. */
    @Nonnull
    private static Message taken(@Nullable DiminishSpec spec) {
        boolean weekly = spec != null && spec.window() == DiminishSpec.Window.WEEKLY;
        return Msg.key(PREFIX + (weekly ? "gate.chest.taken.week" : "gate.chest.taken.day"));
    }
}
