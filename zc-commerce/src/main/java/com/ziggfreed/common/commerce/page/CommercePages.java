package com.ziggfreed.common.commerce.page;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.commerce.asset.WhereAxis;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * The way in to the commerce screens: two calls that open one, and one place a consumer says what
 * those screens should know.
 *
 * <p>{@link #openShop} and {@link #openBoard} are the whole of what the destination handlers do, so
 * routing a storefront or a board is a one-line delegation wherever a block, an NPC, a conversation
 * line or a command wants one.
 *
 * <p><b>A board opens on the quest page.</b> A board is a HOST: it opens the library's quest page
 * with the board as the place the page stands at, so its contracts read, are taken, handed in and
 * collected exactly as a character's quests are. That page lives above this module, so this module
 * declares the opener ({@link BoardPageOpener}) and the library's objectives module fills it at setup
 * ({@link #boardPage}); unfilled, an open answers false and says so once.
 *
 * <p><b>Deps are resolved LAZILY, at open time.</b> A consumer's naming, theme and routing are built
 * long after this module's setup runs, so a supplier is registered once and asked on each open; a
 * consumer that registers nothing gets {@link CommercePageDeps#DEFAULTS}, which is a fully working
 * storefront.
 *
 * <p><b>An unnamed target means "whichever one this server has".</b> A destination may leave the id
 * out, and the honest answer is the first storefront or board the content declares in its own order
 * rather than a hardcoded id this library invented. A server with none declines, which is what a
 * caller already has to cope with.
 *
 * <p><b>Both opens are judged where the player stands.</b> The unnamed default is the first one the
 * player's own world lists ({@link #firstShopId(LoadedWorld)}), so it never lands on a storefront or
 * a board whose {@code Where} names another world; one opened by id there opens closed, as a
 * switched-off one does, because the page asks the same question of the world it is built in.
 *
 * <p>World thread.
 */
public final class CommercePages {

    /**
     * What opens a board: the quest page with the board as its host, which this module cannot see, so
     * the module that owns that page fills this at setup. True means the screen was taken.
     */
    @FunctionalInterface
    public interface BoardPageOpener {

        /**
         * Open the board {@code boardId} (an id this server declares) for {@code player} on the anchor
         * {@code ref}, with {@code highlightBountyId} singled out when it is on the board.
         */
        boolean open(@Nonnull String boardId, @Nullable String highlightBountyId,
                @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player);
    }

    /** Who this module's page registrations are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    private static final AtomicReference<Supplier<CommercePageDeps>> DEPS = new AtomicReference<>();

    private static final AtomicReference<BoardPageOpener> BOARD_PAGE = new AtomicReference<>();

    /** Whether an open with no board page installed has been reported; once is enough. */
    private static final AtomicBoolean WARNED_NO_BOARD_PAGE = new AtomicBoolean();

    private CommercePages() {
    }

    /**
     * Install what opens a board ({@link BoardPageOpener}). The library's objectives module fills it at
     * setup with its quest page; pass null to clear it.
     */
    public static void boardPage(@Nullable BoardPageOpener opener) {
        BOARD_PAGE.set(opener);
    }

    /** Whether something opens a board on this server, which is what a board opener needs to land anywhere. */
    public static boolean hasBoardPage() {
        return BOARD_PAGE.get() != null;
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
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        String id = shopId != null && !shopId.isBlank() ? shopId : firstShopId(WhereAxis.viewer(store));
        if (id == null) {
            SafeLog.fine("[commerce] a storefront was asked for but this server declares none");
            return false;
        }
        // Read the reference off the PLAYER, never off {@code ref}: this seam's ref is the ANCHOR
        // the page is opened on, which is the shopkeeper's own entity at a press-F.
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[commerce] a storefront was asked for by an entity that is not a player");
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

    /** {@link #openBoard(String, String, Store, Ref, Player)} with nothing singled out. */
    public static boolean openBoard(@Nullable String boardId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        return openBoard(boardId, null, store, ref, player);
    }

    /**
     * Open the board {@code boardId} on {@code ref} with {@code openAtBountyId} singled out, or the
     * first board this server declares when the id is left out. The board opens as the host of the
     * library's quest page ({@link BoardPageOpener}), its contract leading the list and open on the
     * right.
     *
     * <p>The second argument is what a deep link passes: a surface that already knows which contract
     * the player pressed opens the board looking at it rather than at whichever row happened to sort
     * first.
     */
    public static boolean openBoard(@Nullable String boardId, @Nullable String openAtBountyId,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        String id = boardId != null && !boardId.isBlank() ? boardId : firstBoardId(WhereAxis.viewer(store));
        if (id == null) {
            SafeLog.fine("[commerce] a board was asked for but this server declares none");
            return false;
        }
        BoardPageOpener opener = boardPageOrWarn();
        if (opener == null) {
            return false;
        }
        try {
            return opener.open(id, openAtBountyId, store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the board page failed to open", t);
            return false;
        }
    }

    /**
     * The installed board opener, or null having REPORTED, once, that there is none: every board, its
     * blocks, its NPCs, its conversation lines and its deep links would open nothing, and nothing else is
     * in a position to notice.
     */
    @Nullable
    static BoardPageOpener boardPageOrWarn() {
        BoardPageOpener opener = BOARD_PAGE.get();
        if (opener == null && WARNED_NO_BOARD_PAGE.compareAndSet(false, true)) {
            SafeLog.warn("[commerce] no board page is installed, so a board opens nothing. The library's"
                    + " objectives module installs its quest page at setup (CommercePages.boardPage); a server"
                    + " that left that setup out shows no board.");
        }
        return opener;
    }

    // ==================== defaults ====================

    /**
     * The first storefront this server declares, in the order the content asked for, skipping one
     * switched off or hidden by a feature ({@link ShopConfig#firstListedId()}). Nobody is looking,
     * so a {@code Where} is not asked: a caller with a player in hand uses
     * {@link #firstShopId(LoadedWorld)}.
     */
    @Nullable
    public static String firstShopId() {
        try {
            return ShopConfig.getInstance().firstListedId();
        } catch (Throwable ignored) {
            // Nothing loaded yet reads as nothing declared, which is the same decline.
            return null;
        }
    }

    /**
     * The first storefront a player standing in {@code viewer}'s world is shown
     * ({@link ShopConfig#firstListedIdIn}): what an unnamed storefront destination opens for them.
     * Read the viewer with {@link WhereAxis#viewer}.
     */
    @Nullable
    public static String firstShopId(@Nonnull LoadedWorld viewer) {
        try {
            return ShopConfig.getInstance().firstListedIdIn(viewer);
        } catch (Throwable ignored) {
            // Nothing loaded yet reads as nothing declared, which is the same decline.
            return null;
        }
    }

    /** The first board this server declares, on the same terms ({@link BoardConfig#firstListedId()}). */
    @Nullable
    public static String firstBoardId() {
        try {
            return BoardConfig.getInstance().firstListedId();
        } catch (Throwable ignored) {
            // Nothing loaded yet reads as nothing declared, which is the same decline.
            return null;
        }
    }

    /**
     * The first board a player standing in {@code viewer}'s world is shown
     * ({@link BoardConfig#firstListedIdIn}), on the same terms as {@link #firstShopId(LoadedWorld)}.
     */
    @Nullable
    public static String firstBoardId(@Nonnull LoadedWorld viewer) {
        try {
            return BoardConfig.getInstance().firstListedIdIn(viewer);
        } catch (Throwable ignored) {
            // Nothing loaded yet reads as nothing declared, which is the same decline.
            return null;
        }
    }
}
