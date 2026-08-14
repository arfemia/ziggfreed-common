package com.ziggfreed.common.commerce.command;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.currency.CurrencyDef;

/**
 * Which wallets this server has, and what each one is made of.
 *
 * <p>Read off the live catalogue, so a pack loaded later simply shows up - and what is listed here
 * is exactly what a price, a reward and a gate can name.
 */
final class CommerceWalletsCommand extends AbstractAsyncCommand {

    CommerceWalletsCommand() {
        super(CommerceCommandLine.WALLETS, CommerceAdminMessages.desc(CommerceCommandLine.WALLETS));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Collection<CurrencyDef> wallets = CommerceCatalogs.currencies().all();
        CommerceAdminMessages.heading(ctx, "wallets.header", wallets.size());
        for (CurrencyDef wallet : wallets) {
            row(ctx, wallet);
        }
        if (wallets.isEmpty()) {
            CommerceAdminMessages.detail(ctx, "wallets.none");
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * One wallet. What BACKS it is the thing worth reading: an item-backed balance is the player's
     * own inventory count and never touches the state store at all, which is why the two read
     * differently here rather than as one line with a flag on it.
     */
    private static void row(@Nonnull CommandContext ctx, @Nonnull CurrencyDef wallet) {
        if (wallet.isItemBacked()) {
            if (wallet.isUncapped()) {
                CommerceAdminMessages.detail(ctx, "wallets.row.item.uncapped", wallet.id(),
                        wallet.backingItemId());
                return;
            }
            CommerceAdminMessages.detail(ctx, "wallets.row.item", wallet.id(),
                    wallet.backingItemId(), wallet.cap());
            return;
        }
        if (wallet.isUncapped()) {
            CommerceAdminMessages.detail(ctx, "wallets.row.counter.uncapped", wallet.id());
            return;
        }
        CommerceAdminMessages.detail(ctx, "wallets.row.counter", wallet.id(), wallet.cap());
    }
}
