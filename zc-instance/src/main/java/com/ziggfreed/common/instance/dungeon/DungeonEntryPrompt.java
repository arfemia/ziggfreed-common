package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.ui.confirm.ItemCostConfirm;
import com.ziggfreed.common.ui.confirm.ItemCostConfirmPage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon's confirm screen in front of its {@code EntryCost} ({@code EntryCost.Confirm}, on by default): the
 * library's item-cost confirm page ({@link ItemCostConfirmPage}) with the dungeon's name as its title, its
 * {@code Text.DescriptionKey} as its description, a slot per item the cost takes (how many the player holds, how many
 * it needs), the cost's {@code HintKey} line while they hold too few, and the cost's {@code ConfirmKey} on the button.
 * The button runs the entry again with no second screen ({@link DungeonEntry#enterConfirmed}), which spends the cost
 * and sends the player in with their party, then the page plays the game's portal sound ({@link #OPEN_SOUND}) and
 * sparkle ({@link #OPEN_PARTICLE}) where they stood; a refusal by then reads as the usual line on the page, and
 * closing it spends nothing.
 */
public final class DungeonEntryPrompt {

    /** The game's portal-opening sound, played where the player stood when they confirm. */
    static final String OPEN_SOUND = "SFX_Portal_Neutral_Open";

    /** The game's portal sparkle, spawned at the player when they confirm. */
    static final String OPEN_PARTICLE = "Portal_Going_Through_Blue";

    private static final String LOG = "[dungeon] ";

    private DungeonEntryPrompt() {
    }

    /**
     * Show {@code player} the confirm screen for {@code dungeon}, priced {@code cost}. Called on their world thread
     * ({@code accessor} is their store, or their interaction chain's command buffer, read here for what they hold);
     * the page opens on a world task of its own, since a page opens through the store, which refuses that while one
     * of its systems runs.
     */
    static void open(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull PlayerRef player, @Nonnull World world, @Nonnull DungeonAsset dungeon, @Nonnull String dungeonId,
            @Nonnull List<DungeonEntryCost.Item> cost) {
        List<ItemCostConfirm.Row> rows = new ArrayList<>(cost.size());
        for (DungeonEntryCost.Item item : cost) {
            rows.add(new ItemCostConfirm.Row(item.itemId(),
                    InventoryUtil.count(accessor, ref, item.itemId(), InventoryUtil.spendableSections()),
                    item.count()));
        }
        ItemCostConfirm spec = new ItemCostConfirm(DungeonRefusals.dungeonName(dungeon),
                text(dungeon.descriptionKey()), rows, text(dungeon.entryCostHintKey()),
                dungeon.entryCostConfirmKey(), OPEN_SOUND, OPEN_PARTICLE);
        world.execute(() -> {
            try {
                if (ref.isValid()) {
                    ItemCostConfirmPage.open(ref, ref.getStore(), player, spec,
                            (r, store, who) -> confirmed(r, store, who, dungeonId));
                }
            } catch (Throwable t) {
                SafeLog.warn(LOG + "the confirm screen of '" + dungeonId + "' could not open for "
                        + player.getUuid(), t);
            }
        });
    }

    /** The button: enter, spending the cost; a refusal tells the player why. Answers whether they were sent. */
    private static boolean confirmed(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull String dungeonId) {
        World world = store.getExternalData().getWorld();
        DungeonEntry.Outcome outcome = DungeonEntry.enterConfirmed(ref, store, player, world, dungeonId);
        if (outcome.result() == DungeonEntry.Result.SENT) {
            return true;
        }
        DungeonRefusals.tell(player, dungeonId, outcome);
        return false;
    }

    @Nullable
    private static Message text(@Nullable String key) {
        return key == null ? null : ContentKeys.tr(key);
    }
}
