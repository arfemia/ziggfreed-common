package com.ziggfreed.common.commerce.page;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to both commerce screens: two calls that open one, and one place a consumer says what
 * those screens should know.
 *
 * <p>{@link #openShop} and {@link #openBoard} are the whole of what the destination handlers do, so
 * routing a storefront or a board is a one-line delegation wherever a block, an NPC, a conversation
 * line or a command wants one.
 *
 * <p><b>Deps are resolved LAZILY, at open time.</b> A consumer's naming, theme and routing are built
 * long after this module's setup runs, so a supplier is registered once and asked on each open; a
 * consumer that registers nothing gets {@link CommercePageDeps#DEFAULTS}, which is a fully working
 * pair of pages.
 *
 * <p><b>An unnamed target means "whichever one this server has".</b> A destination may leave the id
 * out, and the honest answer is the first storefront or board the content declares in its own order
 * rather than a hardcoded id this library invented. A server with none declines, which is what a
 * caller already has to cope with.
 *
 * <p>World thread.
 */
public final class CommercePages {

    /** Who this module's page registrations are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    private static final AtomicReference<Supplier<CommercePageDeps>> DEPS = new AtomicReference<>();

    private CommercePages() {
    }

    /**
     * Say what these pages should know about a consumer's world. Call once from that consumer's
     * setup; pass null to go back to the library defaults.
     */
    public static void deps(@Nullable Supplier<CommercePageDeps> supplier) {
        DEPS.set(supplier);
    }

    /**
     * The deps in force right now: the registered consumer's, else the library defaults. Guarded, so
     * a supplier that throws or answers null costs the consumer's own contributions rather than the
     * screen.
     */
    @Nonnull
    public static CommercePageDeps resolvedDeps() {
        Supplier<CommercePageDeps> supplier = DEPS.get();
        if (supplier == null) {
            return CommercePageDeps.DEFAULTS;
        }
        try {
            CommercePageDeps deps = supplier.get();
            return deps != null ? deps : CommercePageDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[commerce] page deps failed to resolve: " + t.getMessage());
            return CommercePageDeps.DEFAULTS;
        }
    }

    // ==================== opening ====================

    /**
     * Open the storefront {@code shopId} on {@code ref}, or the first one this server declares when
     * it is left out. False means nothing was shown, so the caller still owes the player a response.
     */
    public static boolean openShop(@Nullable String shopId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        String id = shopId != null && !shopId.isBlank() ? shopId : firstShopId();
        if (id == null) {
            SafeLog.fine("[commerce] a storefront was asked for but this server declares none");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store,
                    new ZigShopPage(playerRef, id, resolvedDeps()));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the storefront page failed to open", t);
            return false;
        }
    }

    /** {@link #openBoard(String, String, Store, Ref, PlayerRef, Player)} with nothing singled out. */
    public static boolean openBoard(@Nullable String boardId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        return openBoard(boardId, null, store, ref, playerRef, player);
    }

    /**
     * Open the board {@code boardId} on {@code ref} with {@code openAtBountyId} already showing in
     * the detail panel, or the first board this server declares when the id is left out.
     *
     * <p>The second argument is what a deep link passes: a surface that already knows which contract
     * the player pressed opens the board looking at it rather than at whichever row happened to sort
     * first.
     */
    public static boolean openBoard(@Nullable String boardId, @Nullable String openAtBountyId,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        String id = boardId != null && !boardId.isBlank() ? boardId : firstBoardId();
        if (id == null) {
            SafeLog.fine("[commerce] a board was asked for but this server declares none");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store,
                    new ZigBoardPage(playerRef, id, openAtBountyId, resolvedDeps()));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the board page failed to open", t);
            return false;
        }
    }

    // ==================== defaults ====================

    /** The first storefront this server declares, in the order the content asked for. */
    @Nullable
    public static String firstShopId() {
        try {
            List<StorefrontAsset> listed = ShopConfig.getInstance().listed();
            for (StorefrontAsset asset : listed) {
                if (asset != null && asset.isEnabled() && asset.getId() != null) {
                    return asset.getId();
                }
            }
        } catch (Throwable ignored) {
            // Nothing loaded yet reads as nothing declared, which is the same decline.
        }
        return null;
    }

    /** The first board this server declares, in the order the content asked for. */
    @Nullable
    public static String firstBoardId() {
        try {
            List<BoardAsset> listed = BoardConfig.getInstance().listed();
            for (BoardAsset asset : listed) {
                if (asset != null && asset.isEnabled() && asset.getId() != null) {
                    return asset.getId();
                }
            }
        } catch (Throwable ignored) {
            // Nothing loaded yet reads as nothing declared, which is the same decline.
        }
        return null;
    }
}
