package com.ziggfreed.common.instance.claims;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a player sees when they open a reward chest: their own unclaimed Claims filed under it, and nothing of
 * anyone else's. The one place the chest decides its view, so the chest's Use ({@link ZigOpenSpoilsInteraction}) never
 * names one.
 *
 * <p><b>One page</b> ({@link RewardChestPage}), for every reward chest: everything of the player's in it on one page
 * with no menu rail, what is not an item first and then the items, a click on a line taking that one reward and Take
 * All taking everything, each told by the page's own toast, so nothing is paid unasked; whatever is left in it waits
 * under Claims. A player with nothing waiting reads one line saying so, and no page opens.
 */
public final class SpoilsView {

    private SpoilsView() {
    }

    /**
     * Open the spoils of {@code fight} for the player behind {@code ref}: the chest's one page, or a line saying
     * nothing in it waits for them; false when no page opened. World thread, outside any system's processing. Never
     * throws.
     */
    public static boolean open(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nullable Player player, @Nonnull String fight) {
        if (store == null || ref == null || player == null || !ref.isValid()) {
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        UUID uuid = playerRef == null ? null : playerRef.getUuid();
        if (uuid == null) {
            return false;
        }
        try {
            ClaimsPageDeps deps = ClaimsPageDeps.library();
            if (deps.claims().list(uuid).stream().noneMatch(claim -> claim.fromFight(fight))) {
                tellEmpty(playerRef, deps);
                return false;
            }
            player.getPageManager().openCustomPage(ref, store, new RewardChestPage(playerRef, deps, fight));
            return true;
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the spoils of run=" + fight + " could not open; they wait under Claims", t);
            return false;
        }
    }

    /** The one line a player with nothing waiting in a chest reads, beside the chest's picture. */
    private static void tellEmpty(@Nonnull PlayerRef playerRef, @Nonnull ClaimsPageDeps deps) {
        ToastDelivery.deliver(playerRef, ToastSpec.of(ToastKind.INFO, deps.text().line("spoils.empty"))
                .withIcon(ClaimsDestinations.ICON), null);
    }
}
