package com.ziggfreed.common.instance.claims;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.claim.ClaimStore;
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
 * under Claims. A player with nothing waiting reads one line saying so, and no page opens. The chest's file's
 * {@code View.ItemsWindow} no longer changes the view.
 *
 * <p>The game's chest window ({@link SpoilsWindow}, {@link #openItems}) is no longer how a chest opens; it is what
 * {@link ClaimsPage}'s chest mode still offers, which nothing in the library opens now.
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

    /**
     * The items {@code fight} left the player, in the game's chest window; the Claims page filtered to the fight when
     * the window will not open; one line when no item waits. True when a window or page opened, false when only the
     * line was told. World thread. {@link ClaimsPage}'s chest mode is its one caller.
     */
    static boolean openItems(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player,
            @Nonnull PlayerRef playerRef, @Nonnull UUID uuid, @Nonnull String fight) {
        ClaimsPageDeps deps = ClaimsPageDeps.library();
        ClaimStore claims = deps.claims();
        if (!hasItems(claims, uuid, fight)) {
            tellEmpty(playerRef, deps);
            return false;
        }
        if (SpoilsWindow.open(store, ref, player, uuid, fight, claims)) {
            return true;
        }
        // The window would not open (or holds no item this server knows): the same Claims, on the Claims page.
        SafeLog.info(SpoilsChests.LOG + "no window for run=" + fight + "; the Claims page shows the spoils instead");
        return openClaimsPage(store, ref, player, playerRef, fight);
    }

    /** Whether any of {@code player}'s Claims from {@code fight} holds an item. */
    static boolean hasItems(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String fight) {
        return claims.list(player).stream().anyMatch(claim -> claim.fromFight(fight)
                && claim.rewards().stream().anyMatch(InstanceReward::isItem));
    }

    /** The Claims page, filtered to the fight. */
    private static boolean openClaimsPage(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull String fight) {
        player.getPageManager().openCustomPage(ref, store,
                new ClaimsPage(playerRef, ClaimsPageDeps.library(), null, fight));
        return true;
    }
}
