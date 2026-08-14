package com.ziggfreed.common.commerce.fold;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.commerce.page.CommercePages;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopValidator;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.validation.Finding;

/**
 * The two destinations a server with an economy has: a storefront, and a board of contracts.
 *
 * <p><b>Unprefixed, because the library owns them.</b> A namespace names the vocabulary's OWNER, and
 * the engines and the pages behind both of these live here - so a mod prefix would be a false claim
 * the day a second consumer ships a shop. A block, an NPC and a conversation line all open them
 * through the one routing value, so a fourth spelling of "open a page" never exists:
 *
 * <pre>{@code
 * "Open": "Shop"                                    the default storefront
 * "Open": { "Type": "Shop", "Shop": "General" }     a named one
 * "Open": { "Type": "Board", "Board": "Daily" }     a named board
 * }</pre>
 *
 * <p><b>An UNNAMED target means "whichever one this server has".</b> The commonest authored form is
 * the bare word - a shop block, a bounty master, a conversation line that is already about one
 * place - so leaving the id out opens the first storefront or board the content declares rather than
 * declining. A server that declares none declines, which is a decline the caller already has to cope
 * with, since it still owes the player whatever it would have done anyway.
 *
 * <p>The page is opened on the moment's own anchor ({@code pageAnchor}), so pressing F on a
 * shopkeeper opens the storefront on that character exactly as the engine's own NPC pages behave,
 * while a block or a command opens it on the player.
 *
 * <p>Each type audits its OWN field, which is the half only this mod can answer: a storefront or a
 * board nothing defines is a WARNING rather than an error, because the pack that defines it may
 * simply not be installed on the server doing the checking.
 */
public final class CommerceDestinations {

    /** The owner every registration here is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id of the storefront destination. */
    public static final String SHOP_TYPE = "Shop";

    /** The {@code Type} id of the board destination. */
    public static final String BOARD_TYPE = "Board";

    /** Reported against a destination naming a storefront no layer defines. */
    public static final String UNKNOWN_SHOP = "UNKNOWN_SHOP";

    /** Reported against a destination naming a board no layer defines. */
    public static final String UNKNOWN_BOARD = "UNKNOWN_BOARD";

    private CommerceDestinations() {
    }

    /**
     * Seed both types into the shared vocabulary. Called by the wiring root at {@code setup()},
     * before assets load, because a file naming a {@code Type} nothing registered fails to read.
     */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(
                        SHOP_TYPE, Shop.class, Shop.CODEC, CommerceDestinations::openShop)
                .withCheck(CommerceDestinations::checkShop));
        Destinations.register(OWNER, DestinationType.of(
                        BOARD_TYPE, Board.class, Board.CODEC, CommerceDestinations::openBoard)
                .withCheck(CommerceDestinations::checkBoard));
    }

    // ==================== Shop ====================

    /** Open a storefront. Name one, or leave it out for whichever the moment is already about. */
    public static final class Shop extends Destination {

        @Nullable protected String shop;

        public static final BuilderCodec<Shop> CODEC = BuilderCodec.builder(Shop.class, Shop::new)
                .append(new KeyedCodec<>("Shop", Codec.STRING, false),
                        (d, v) -> d.shop = v, d -> d.shop)
                .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.SHOPS)))
                .documentation("Which storefront to open, by id. Leave it out where the thing being "
                        + "opened already says which one - a shop block, a shopkeeper - and name one "
                        + "to point somewhere else.").add()
                .build();

        public Shop() {
        }

        /** Java-side construction; a null id means whichever storefront the moment is about. */
        @Nonnull
        public static Shop of(@Nullable String shopId) {
            Shop d = new Shop();
            d.shop = shopId;
            return d;
        }

        /** The storefront id, or null for the one the moment is already about. */
        @Nullable
        public String getShop() {
            return trimToNull(shop);
        }
    }

    private static boolean openShop(@Nonnull Shop destination, @Nonnull DestinationContext ctx) {
        return CommercePages.openShop(destination.getShop(), ctx.store(), ctx.pageAnchor(),
                ctx.playerRef(), ctx.player());
    }

    @Nonnull
    private static List<Finding> checkShop(@Nonnull Shop destination, @Nonnull String sourceId) {
        String id = destination.getShop();
        if (id == null || ShopConfig.getInstance().has(id)) {
            return List.of();
        }
        return List.of(Finding.warning(ShopValidator.DOMAIN, UNKNOWN_SHOP,
                "opens the storefront '" + id + "', which no layer defines - the pack that ships it may "
                        + "simply not be installed here", sourceId));
    }

    // ==================== Board ====================

    /** Open a board of contracts. Name one, or leave it out for whichever the moment is about. */
    public static final class Board extends Destination {

        @Nullable protected String board;

        public static final BuilderCodec<Board> CODEC = BuilderCodec.builder(Board.class, Board::new)
                .append(new KeyedCodec<>("Board", Codec.STRING, false),
                        (d, v) -> d.board = v, d -> d.board)
                .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.BOARDS)))
                .documentation("Which board to open, by id. Leave it out where the thing being opened "
                        + "already says which one - a board block, a bounty master - and name one to "
                        + "point somewhere else.").add()
                .build();

        public Board() {
        }

        /** Java-side construction; a null id means whichever board the moment is about. */
        @Nonnull
        public static Board of(@Nullable String boardId) {
            Board d = new Board();
            d.board = boardId;
            return d;
        }

        /** The board id, or null for the one the moment is already about. */
        @Nullable
        public String getBoard() {
            return trimToNull(board);
        }
    }

    private static boolean openBoard(@Nonnull Board destination, @Nonnull DestinationContext ctx) {
        return CommercePages.openBoard(destination.getBoard(), ctx.store(), ctx.pageAnchor(),
                ctx.playerRef(), ctx.player());
    }

    @Nonnull
    private static List<Finding> checkBoard(@Nonnull Board destination, @Nonnull String sourceId) {
        String id = destination.getBoard();
        if (id == null || BoardConfig.getInstance().has(id)) {
            return List.of();
        }
        return List.of(Finding.warning(BoardValidator.DOMAIN, UNKNOWN_BOARD,
                "opens the board '" + id + "', which no layer defines - the pack that ships it may "
                        + "simply not be installed here", sourceId));
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
