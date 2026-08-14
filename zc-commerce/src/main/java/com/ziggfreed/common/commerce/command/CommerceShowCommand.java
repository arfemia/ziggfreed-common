package com.ziggfreed.common.commerce.command;

import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.RerollState;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.commerce.fold.ShopEntryOffer;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.ShopEngine;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.subject.Subject;

/**
 * Everything this server remembers about one player's economy: their counter balances, what they
 * have bought and how often, and which rotating positions they have re-rolled this period.
 *
 * <p><b>It reads the CATALOGUE and asks the store about it, rather than asking the store what it
 * holds.</b> That is what makes it work against any installed store - the seam answers per offer and
 * per pool, deliberately, because "list everything you have" is a question a database-backed
 * implementation should never be asked casually.
 *
 * <p>Reroll state is shown for the CURRENT period only, which is the only period that means
 * anything: a rotation carries no history and a past period's state has already stopped being read.
 *
 * <p>A store that cannot keep purchases or rerolls says so once instead of printing rows of zeroes
 * that look like a player who has bought nothing.
 */
final class CommerceShowCommand extends TargetPlayerSubCommand {

    CommerceShowCommand() {
        super(CommerceCommandLine.SHOW);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Subject subject) {
        long nowMs = System.currentTimeMillis();
        CommerceStore store = CommerceStores.get();
        CommerceAdminMessages.heading(ctx, "show.header", subject.name());
        balances(ctx, store, subject);
        purchases(ctx, store, subject, nowMs);
        rerolls(ctx, store, subject, nowMs);
    }

    // ==================== the wallet ====================

    private static void balances(@Nonnull CommandContext ctx, @Nonnull CommerceStore store,
            @Nonnull Subject subject) {
        CommerceAdminMessages.heading(ctx, "show.balances");
        Map<String, Long> balances = store.balances(subject);
        if (balances.isEmpty()) {
            CommerceAdminMessages.detail(ctx, "show.balances.none");
            return;
        }
        for (Map.Entry<String, Long> entry : balances.entrySet()) {
            CommerceAdminMessages.detail(ctx, "show.balance.row", entry.getKey(), entry.getValue(),
                    store.lifetimeSpent(subject, entry.getKey()));
        }
    }

    // ==================== what they have bought ====================

    private static void purchases(@Nonnull CommandContext ctx, @Nonnull CommerceStore store,
            @Nonnull Subject subject, long nowMs) {
        CommerceAdminMessages.heading(ctx, "show.purchases");
        if (!store.recordsPurchases()) {
            CommerceAdminMessages.detail(ctx, "show.store.no_purchases");
            return;
        }
        long epochDay = ShopEngine.epochDay(nowMs);
        int shown = 0;
        for (ShopEntryOffer offer : CommerceCatalogs.shopContent().offers()) {
            String offerId = offer.offerId();
            int total = store.purchasesTotal(subject, offerId);
            if (total <= 0) {
                continue;
            }
            shown++;
            PurchaseLimits limits = offer.limits();
            CommerceAdminMessages.detail(ctx, "show.purchase.row", offerId,
                    store.purchasesToday(subject, offerId, epochDay), total, limitText(limits));
        }
        if (shown == 0) {
            CommerceAdminMessages.detail(ctx, "show.purchases.none");
        }
    }

    /** The authored ceiling as an author wrote it, or a dash where there is none. */
    @Nonnull
    private static String limitText(@Nonnull PurchaseLimits limits) {
        String daily = limits.daily() == null ? "-" : String.valueOf(limits.daily());
        String total = limits.total() == null ? "-" : String.valueOf(limits.total());
        return daily + "/" + total;
    }

    // ==================== what they have re-rolled ====================

    private static void rerolls(@Nonnull CommandContext ctx, @Nonnull CommerceStore store,
            @Nonnull Subject subject, long nowMs) {
        CommerceAdminMessages.heading(ctx, "show.rerolls");
        if (!store.recordsRerolls()) {
            CommerceAdminMessages.detail(ctx, "show.store.no_rerolls");
            return;
        }
        int shown = 0;
        for (ShopPoolAsset shelf : ShopPoolConfig.getInstance().all().values()) {
            String poolId = shelf.getId();
            if (poolId == null || poolId.isBlank()) {
                continue;
            }
            long period = CommerceFold.rotation(shelf.getRotation(), poolId).periodIndex(nowMs);
            shown += row(ctx, store, subject, poolId, period) ? 1 : 0;
        }
        for (BoardAsset board : BoardConfig.getInstance().all().values()) {
            String boardId = board.getId();
            if (boardId == null || boardId.isBlank()) {
                continue;
            }
            long period = BoardAssetSpec.of(board).rotation().periodIndex(nowMs);
            shown += row(ctx, store, subject, boardId, period) ? 1 : 0;
        }
        if (shown == 0) {
            CommerceAdminMessages.detail(ctx, "show.rerolls.none");
        }
    }

    /** One pool's row, or nothing at all when this player has not touched it this period. */
    private static boolean row(@Nonnull CommandContext ctx, @Nonnull CommerceStore store,
            @Nonnull Subject subject, @Nonnull String poolId, long period) {
        RerollState state = store.rerollState(subject, poolId, period);
        if (state.isEmpty()) {
            return false;
        }
        CommerceAdminMessages.detail(ctx, "show.reroll.row", poolId, period, state.spent(),
                state.overrides().size());
        return true;
    }
}
