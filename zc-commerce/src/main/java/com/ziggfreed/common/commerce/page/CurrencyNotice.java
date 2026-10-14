package com.ziggfreed.common.commerce.page;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyObserver;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * Telling a player what a wallet is FOR the first time they earn it, where they are looking (M527): one
 * toast naming the wallet ("New: Hallow Sweets") over one row, the wallet's picture beside its name and the
 * line its file authors as its description ({@link CurrencyText#descriptionOf}), drawn into the page they have open
 * or else in the corner ({@link ToastDelivery#deliverWhenSettled}). It hands nothing over.
 *
 * <p><b>Once and for good, per player per wallet.</b> The mark is the commerce store's one-time claim
 * ({@code claimMigration}), taken only when the notice is about to show, so a store that cannot keep a mark
 * never shows it rather than showing it every login. A player who already held the wallet before it had a
 * description hears of it at their next earn.
 *
 * <p><b>Only a wallet with something to say, while it is listed.</b> No description authored means no
 * notice, and a wallet out of its season (or otherwise out of listings, {@link CurrencyAsset#isListed()})
 * stays quiet and claims nothing, so its notice waits for the first earn while it is shown.
 *
 * <p>Ranked as a progress notice ({@link ToastSpec#PRIORITY_PROGRESS}), the lowest rank: a claim receipt
 * raised in the same moment keeps the page's toast slot and this one waits its turn behind it.
 *
 * <p>Heard through {@link #OBSERVER}, which {@code CommerceDefaults} adds after the installed currency
 * engine's own observer, the library's default engine and a consumer's replacement alike: it works on a
 * server running only this library, and a consumer's own earn announcement is untouched.
 */
public final class CurrencyNotice {

    /** The headline, the wallet's name in {0}. */
    private static final String HEADLINE_KEY = "ziggfreedcommon.commerce.wallet.toast.new";

    /**
     * The row, the wallet's name in {0} and its description in {1}: the corner feed drops a toast's headline
     * when it has rows, so the row names the wallet itself.
     */
    private static final String ROW_KEY = "ziggfreedcommon.commerce.wallet.toast.new.row";

    /** The one-time mark's prefix; the wallet's lower-cased id follows it. */
    private static final String MARK_PREFIX = "ziggfreedcommon:currency-noticed:";

    /** Hears every genuine earn; a spend says nothing. */
    public static final CurrencyObserver OBSERVER = new CurrencyObserver() {
        @Override
        public void earned(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount, long balance) {
            onEarned(subject, currency, amount);
        }
    };

    private CurrencyNotice() {
    }

    /** The mark one player's notice for {@code currencyId} is claimed under. */
    @Nonnull
    static String markOf(@Nonnull String currencyId) {
        return MARK_PREFIX + currencyId.trim().toLowerCase(Locale.ROOT);
    }

    /** Show the notice if this earn is the player's first of a described, listed wallet. Never throws. */
    private static void onEarned(@Nonnull Subject subject, @Nonnull CurrencyDef currency, long amount) {
        try {
            if (amount <= 0L) {
                return;
            }
            Message description = CurrencyText.descriptionOf(currency);
            if (description == null) {
                return;
            }
            CurrencyAsset asset = CurrencyConfig.getInstance().resolve(currency.id());
            if (asset == null || !asset.isListed()) {
                return;
            }
            PlayerRef playerRef = playerRefOf(subject);
            if (playerRef == null) {
                return;
            }
            if (!CommerceStores.get().claimMigration(subject, markOf(currency.id()))) {
                return;
            }
            Message name = CurrencyText.nameOf(currency, CommercePages.resolvedDeps().currencyNames());
            ToastDelivery.deliverWhenSettled(playerRef, toast(currency, name, description), null);
        } catch (Throwable t) {
            SafeLog.fine("[commerce] the first-earn notice for '" + currency.id() + "' did not show: "
                    + t.getMessage());
        }
    }

    /**
     * The toast: the headline naming the wallet, and one row carrying the wallet's picture beside its name and
     * what it is for. The picture and the name ride the row because the corner feed shows a toast's rows
     * alone, so the row is what a player with no page open reads.
     */
    @Nonnull
    static ToastSpec toast(@Nonnull CurrencyDef currency, @Nonnull Message name, @Nonnull Message description) {
        String icon = CurrencyText.iconOf(currency);
        Message line = Msg.key(ROW_KEY, name, description);
        ToastLine row = icon == null ? ToastLine.text(line) : ToastLine.item(icon, 1, line);
        return ToastSpec.of(ToastKind.INFO, Msg.key(HEADLINE_KEY, name))
                .withLines(List.of(row))
                .withPriority(ToastSpec.PRIORITY_PROGRESS);
    }

    /** The live player behind {@code subject}: its reference, or the reference of its live entity. */
    @Nullable
    private static PlayerRef playerRefOf(@Nonnull Subject subject) {
        PlayerRef playerRef = subject.handleAs(PlayerRef.class);
        if (playerRef != null) {
            return playerRef;
        }
        Player player = subject.handleAs(Player.class);
        return player == null ? null : PlayerAccess.playerRef(player);
    }
}
