package com.ziggfreed.common.commerce.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.commerce.fold.ShopEntryOffer;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;

/**
 * The storefronts this server has, the shelves standing in them, and where each shelf is in its
 * rotation right now.
 *
 * <p>Naming one with {@code --shop} also lists its offers, which is the level of detail that turns a
 * long catalogue into noise when it is printed unasked.
 *
 * <p><b>What "rotation state" is, and what it is not.</b> A shelf's period is a pure function of its
 * cadence and the clock, so what is shown here - which period it is in and how long until the next -
 * is the whole of the state there is. There is no stored schedule to inspect and nothing to force,
 * by design: that is what makes every player see the same shelf and a restart show what was there
 * before.
 */
final class CommerceShopsCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> shopArg;

    CommerceShopsCommand() {
        super(CommerceCommandLine.SHOPS, CommerceAdminMessages.desc(CommerceCommandLine.SHOPS));
        this.shopArg = withOptionalArg("shop", CommerceAdminMessages.desc("arg.shop"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        long nowMs = System.currentTimeMillis();
        String wanted = shopArg.provided(ctx) ? shopArg.get(ctx) : null;
        List<StorefrontAsset> shops = ShopConfig.getInstance().listed();
        CommerceAdminMessages.heading(ctx, "shops.header", shops.size());
        if (shops.isEmpty()) {
            CommerceAdminMessages.detail(ctx, "shops.none");
            return CompletableFuture.completedFuture(null);
        }
        for (StorefrontAsset shop : shops) {
            String shopId = shop.getId();
            if (shopId == null || (wanted != null && !wanted.equalsIgnoreCase(shopId))) {
                continue;
            }
            shop(ctx, shopId, nowMs, wanted != null);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void shop(@Nonnull CommandContext ctx, @Nonnull String shopId, long nowMs,
            boolean withOffers) {
        List<ShopPoolAsset> shelves = ShopPoolConfig.getInstance().shelvesOf(shopId);
        List<ShopEntryOffer> offers = CommerceCatalogs.shopContent().offersOf(shopId);
        CommerceAdminMessages.heading(ctx, "shops.row", shopId, shelves.size(), offers.size());
        for (ShopPoolAsset shelf : shelves) {
            shelf(ctx, shelf, nowMs);
        }
        if (!withOffers) {
            return;
        }
        for (ShopEntryOffer offer : offers) {
            // Two lines rather than one with a flag on it: a shipped "false" is a word nobody
            // translated, and a disabled offer is exactly what somebody is looking for here.
            CommerceAdminMessages.detail(ctx, offer.enabled() ? "shops.offer" : "shops.offer.disabled",
                    offer.offerId(), text(offer.poolId(), "-"));
        }
    }

    private static void shelf(@Nonnull CommandContext ctx, @Nonnull ShopPoolAsset shelf, long nowMs) {
        String shelfId = text(shelf.getId(), "");
        RotationSpec rotation = CommerceFold.rotation(shelf.getRotation(), shelfId);
        CommerceAdminMessages.detail(ctx, "shops.shelf", shelfId, rotation.periodIndex(nowMs),
                minutes(rotation, nowMs),
                CommerceCatalogs.shops().poolCandidates(shelfId).size());
    }

    /**
     * How long until this shelf turns over, in whole minutes. A cadence nothing authored never
     * rotates, and the engine says so by answering a period length of zero rather than by having a
     * schedule, so the wait it reports is zero too.
     */
    private static long minutes(@Nonnull RotationSpec rotation, long nowMs) {
        return Math.max(0L, rotation.millisUntilNext(nowMs) / 60_000L);
    }

    @Nonnull
    private static String text(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
