package com.ziggfreed.common.commerce.page;

import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What a consumer may say about the two commerce screens without owning either.
 *
 * <p>Every seam here has a DEFAULT that leaves both pages fully working on a bare server: the
 * offers, the boards, the wallets and the prices all come from the authored content this library
 * already folds, and the words come from its own lang file. Nothing in this class is required for a
 * page to run - a consumer fills a seam to say something the library genuinely cannot know, and
 * nothing else.
 *
 * <p><b>ONE deps object for BOTH pages, deliberately.</b> A storefront and a board are two faces of
 * one economy: they name the same wallets, read the same generated content, paint the same reward
 * chips and wear the same theme. Splitting them would make a consumer say all of that twice and let
 * the two copies drift, which is the exact failure a shared seam exists to prevent.
 *
 * <ul>
 *   <li>{@link PageTheme} - how the frame is painted, for a consumer shipping a theme.</li>
 *   <li>{@link CurrencyText.Source} - what a WALLET is called, when a mod names its currencies
 *       somewhere of its own. Unfilled, a wallet reads by its authored name key, else by its backing
 *       item's own engine name.</li>
 *   <li>{@link CommerceText.ArgResolver} - what a generated row's text ARGUMENT means. This is the
 *       one seam a mod shipping generated content really should fill: a family generated per skill
 *       writes the skill ID into its arguments, and only the mod that owns those ids can turn one
 *       into a word a player reads.</li>
 *   <li>{@link RewardChips.Source} - how ONE reward reads, when a mod knows something about its own
 *       kind the generic reading cannot recover. The generic reading is tried when this returns
 *       null, never instead of it.</li>
 *   <li>{@link PurchaseToast} - the toast a consumer floats when something is bought, so a purchase
 *       made here reads exactly like the same purchase made in that mod's own menu.</li>
 * </ul>
 *
 * <p>A board opens on the library's quest page since 2.4.0 ({@link CommercePages#openBoard}), which reads
 * this record's {@link #currencyNames} and {@link #titleArgs} for a board's wallets, title and grade
 * words and takes its theme, toasts and hand-off from the quest page's own deps.
 *
 * <p>Immutable; build one at setup and hand the same instance back on every open.
 */
public final class CommercePageDeps {

    /**
     * How a page's root template reaches the screen. A consumer with a theme appends it and retints
     * the frame in one call; the default simply appends it.
     */
    @FunctionalInterface
    public interface PageTheme {

        /** Append {@code template} and paint whatever theme the consumer has for {@code frameSelector}. */
        void appendThemed(@Nonnull UICommandBuilder cmd, @Nonnull String template,
                @Nonnull String frameSelector);
    }

    /** The toast a consumer floats when a purchase goes through; null for the library's own line. */
    @FunctionalInterface
    public interface PurchaseToast {

        @Nullable
        ToastSpec forPurchase(@Nonnull String offerId);
    }

    /** Append and nothing else: the honest default for a server shipping no theme. */
    public static final PageTheme PLAIN_THEME = (cmd, template, frameSelector) -> cmd.append(template);

    /** No consumer toast, so the page floats its own line. */
    public static final PurchaseToast NO_PURCHASE_TOAST = offerId -> null;

    /** Everything at its library default: two pages that work on a server running nothing else. */
    public static final CommercePageDeps DEFAULTS = builder().build();

    @Nonnull private final PageTheme theme;
    @Nonnull private final CurrencyText.Source currencyNames;
    @Nonnull private final CommerceText.ArgResolver titleArgs;
    @Nonnull private final RewardChips.Source rewardChips;
    @Nonnull private final PurchaseToast purchaseToast;

    private CommercePageDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.currencyNames = builder.currencyNames;
        this.titleArgs = builder.titleArgs;
        this.rewardChips = builder.rewardChips;
        this.purchaseToast = builder.purchaseToast;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public PageTheme theme() {
        return theme;
    }

    @Nonnull
    public CurrencyText.Source currencyNames() {
        return currencyNames;
    }

    @Nonnull
    public CommerceText.ArgResolver titleArgs() {
        return titleArgs;
    }

    @Nonnull
    public RewardChips.Source rewardChips() {
        return rewardChips;
    }

    @Nonnull
    public PurchaseToast purchaseToast() {
        return purchaseToast;
    }

    /**
     * The toast for a purchase of {@code offerId}, guarded: the consumer's own when it answers one, else
     * {@code libraryLine}'s. Either way it plays {@link ToastSounds#PURCHASE} unless it names a sound of its
     * own or was silenced on purpose: no moment fires for a purchase, so the toast owns the sound on every
     * server. A consumer toast that throws costs its own line, never the purchase that earned it.
     */
    @Nonnull
    public ToastSpec resolvePurchaseToast(@Nonnull String offerId, @Nonnull Supplier<ToastSpec> libraryLine) {
        ToastSpec spec = null;
        try {
            spec = purchaseToast.forPurchase(offerId);
        } catch (Throwable ignored) {
            // A consumer's toast failing costs its own line, never the purchase that earned it.
        }
        return ToastSounds.orDefault(spec != null ? spec : libraryLine.get(), ToastSounds.PURCHASE);
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private PageTheme theme = PLAIN_THEME;
        @Nonnull private CurrencyText.Source currencyNames = CurrencyText.AUTHORED_ONLY;
        @Nonnull private CommerceText.ArgResolver titleArgs = CommerceText.RAW_ARGS;
        @Nonnull private RewardChips.Source rewardChips = RewardChips.GENERIC;
        @Nonnull private PurchaseToast purchaseToast = NO_PURCHASE_TOAST;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable PageTheme value) {
            this.theme = value != null ? value : PLAIN_THEME;
            return this;
        }

        @Nonnull
        public Builder currencyNames(@Nullable CurrencyText.Source value) {
            this.currencyNames = value != null ? value : CurrencyText.AUTHORED_ONLY;
            return this;
        }

        @Nonnull
        public Builder titleArgs(@Nullable CommerceText.ArgResolver value) {
            this.titleArgs = value != null ? value : CommerceText.RAW_ARGS;
            return this;
        }

        @Nonnull
        public Builder rewardChips(@Nullable RewardChips.Source value) {
            this.rewardChips = value != null ? value : RewardChips.GENERIC;
            return this;
        }

        @Nonnull
        public Builder purchaseToast(@Nullable PurchaseToast value) {
            this.purchaseToast = value != null ? value : NO_PURCHASE_TOAST;
            return this;
        }

        @Nonnull
        public CommercePageDeps build() {
            return new CommercePageDeps(this);
        }
    }
}
