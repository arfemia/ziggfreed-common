package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.List;
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
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a player sees when they open a spoils chest: their own unclaimed Claims from its fight, and nothing of
 * anyone else's. The one place the chest decides its view, so the chest's Use ({@link ZigOpenSpoilsInteraction}) never
 * names one, and {@link #VANILLA_WINDOW} is the one switch between the two views.
 *
 * <p><b>The game's chest window</b> (the default): what is not an item (a currency, an experience or reputation
 * token) is handed over at once through the same grant a Claim press makes ({@link ClaimGrants#claimFightExceptItems}),
 * with one toast listing it; the items then open in the game's own chest window ({@link SpoilsWindow}), from which
 * they only go into the bag, and whatever is left in it stays under Claims. A player with nothing waiting reads one
 * line saying so, and no window opens. A second use while their window is open does nothing. A window the engine
 * will not open falls back to the Claims page below, so the items are never out of reach.
 *
 * <p><b>The Claims page</b> (the switch off): the Claims page filtered to the fight ({@link ClaimsPage}'s spoils mode:
 * only those Claims, Claim and Take All, one line when nothing waits).
 */
public final class SpoilsView {

    /** True opens the game's chest window; false the Claims page filtered to the fight. */
    static final boolean VANILLA_WINDOW = true;

    private SpoilsView() {
    }

    /**
     * Open the spoils of {@code fight} for the player behind {@code ref}; false when nothing could open. World thread,
     * outside any system's processing (a non-item payout may write the player's components). Never throws.
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
            return VANILLA_WINDOW ? openWindow(store, ref, player, playerRef, uuid, fight)
                    : openClaimsPage(store, ref, player, playerRef, fight);
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the spoils of run=" + fight + " could not open; they wait under Claims", t);
            return false;
        }
    }

    /** The game's chest window: pay the non-items, then show the items; one line when nothing is left. */
    private static boolean openWindow(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull UUID uuid, @Nonnull String fight) {
        if (SpoilsWindow.isOpen(uuid, player)) {
            return true;
        }
        ClaimsPageDeps deps = ClaimsPageDeps.library();
        ClaimStore claims = deps.claims();
        ClaimGrants.Paid paid = ClaimGrants.claimFightExceptItems(claims, uuid, fight, ref, store, deps.sink());
        ToastSpec told = paidToast(paid, deps.text());
        if (told != null) {
            ToastDelivery.deliver(playerRef, told, null);
        }
        boolean items = claims.list(uuid).stream().anyMatch(claim -> claim.fromFight(fight)
                && claim.rewards().stream().anyMatch(InstanceReward::isItem));
        if (!items) {
            if (told == null) {
                ToastDelivery.deliver(playerRef, ToastSpec.of(ToastKind.INFO, deps.text().line("spoils.empty"))
                        .withIcon(ClaimsDestinations.ICON), null);
            }
            return true;
        }
        if (SpoilsWindow.open(store, ref, player, uuid, fight, claims)) {
            return true;
        }
        // The window would not open (or holds no item this server knows): the same Claims, on the Claims page.
        SafeLog.info(SpoilsChests.LOG + "no window for run=" + fight + "; the Claims page shows the spoils instead");
        return openClaimsPage(store, ref, player, playerRef, fight);
    }

    /** The Claims page, filtered to the fight. */
    private static boolean openClaimsPage(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull String fight) {
        player.getPageManager().openCustomPage(ref, store,
                new ClaimsPage(playerRef, ClaimsPageDeps.library(), null, fight));
        return true;
    }

    /**
     * The one toast a chest's non-item payout raises: a row per reward handed over, as its reward chip reads; the
     * press's own warning when part of it could not be paid; null when nothing was.
     */
    @Nullable
    static ToastSpec paidToast(@Nonnull ClaimGrants.Paid paid, @Nonnull ClaimsText text) {
        if (paid.result().nothing() || paid.rewards().isEmpty()) {
            return null;
        }
        if (paid.result().failed() > 0) {
            return ToastSpec.of(ToastKind.WARNING, text.line("toast.failed"));
        }
        List<ToastLine> rows = new ArrayList<>(paid.rewards().size());
        for (InstanceReward reward : paid.rewards()) {
            RewardChip chip = ClaimsView.chip(reward);
            rows.add(chip.iconItemId() == null || chip.iconItemId().isBlank() ? ToastLine.text(chip.label())
                    : ToastLine.item(chip.iconItemId(), 1, chip.label()));
        }
        return ToastSpec.of(ToastKind.REWARD, text.line("spoils.paid"))
                .withIcon(ClaimsDestinations.ICON)
                .withLines(rows)
                .withSound(ToastSounds.RECEIPT);
    }
}
