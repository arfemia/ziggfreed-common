package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.loot.reward.RewardChips;
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
 *   <li>{@link PurchaseToast} / {@link CompletionToast} - the toast a consumer floats when something
 *       is bought or a contract settles, so an action taken here reads exactly like the same action
 *       taken in that mod's own menu.</li>
 *   <li>{@link CompletionHandOff} - what FOLLOWS a contract settled at a board. The routing policy
 *       belongs to the dialogue layer, which sits beside this module rather than under it, so the
 *       wiring root fills this and the page merely hosts the beat.</li>
 * </ul>
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

    /** The toast a consumer floats when a contract settles; null for the library's own line. */
    @FunctionalInterface
    public interface CompletionToast {

        @Nullable
        ToastSpec forCompleted(@Nonnull String bountyId);
    }

    /**
     * What follows a contract that settled at a board: a conversation, a screen of the consumer's
     * own, or nothing.
     *
     * <p>Return true ONLY when something else took the screen, in which case the page stops
     * refreshing itself - the player is looking at whatever replaced it.
     */
    @FunctionalInterface
    public interface CompletionHandOff {

        boolean handOff(@Nonnull String bountyId, @Nullable String boardId,
                @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player);
    }

    /** Append and nothing else: the honest default for a server shipping no theme. */
    public static final PageTheme PLAIN_THEME = (cmd, template, frameSelector) -> cmd.append(template);

    /** No consumer toast, so the page floats its own line. */
    public static final PurchaseToast NO_PURCHASE_TOAST = offerId -> null;

    /** No consumer toast, so the page floats its own line. */
    public static final CompletionToast NO_COMPLETION_TOAST = bountyId -> null;

    /** Nothing follows a settled contract, so the page keeps the screen and refreshes itself. */
    public static final CompletionHandOff NO_HAND_OFF =
            (bountyId, boardId, store, ref, player) -> false;

    /** Everything at its library default: two pages that work on a server running nothing else. */
    public static final CommercePageDeps DEFAULTS = builder().build();

    @Nonnull private final PageTheme theme;
    @Nonnull private final CurrencyText.Source currencyNames;
    @Nonnull private final CommerceText.ArgResolver titleArgs;
    @Nonnull private final RewardChips.Source rewardChips;
    @Nonnull private final PurchaseToast purchaseToast;
    @Nonnull private final CompletionToast completionToast;
    @Nonnull private final CompletionHandOff completion;

    private CommercePageDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.currencyNames = builder.currencyNames;
        this.titleArgs = builder.titleArgs;
        this.rewardChips = builder.rewardChips;
        this.purchaseToast = builder.purchaseToast;
        this.completionToast = builder.completionToast;
        this.completion = builder.completion;
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

    @Nonnull
    public CompletionToast completionToast() {
        return completionToast;
    }

    @Nonnull
    public CompletionHandOff completion() {
        return completion;
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private PageTheme theme = PLAIN_THEME;
        @Nonnull private CurrencyText.Source currencyNames = CurrencyText.AUTHORED_ONLY;
        @Nonnull private CommerceText.ArgResolver titleArgs = CommerceText.RAW_ARGS;
        @Nonnull private RewardChips.Source rewardChips = RewardChips.GENERIC;
        @Nonnull private PurchaseToast purchaseToast = NO_PURCHASE_TOAST;
        @Nonnull private CompletionToast completionToast = NO_COMPLETION_TOAST;
        @Nonnull private CompletionHandOff completion = NO_HAND_OFF;

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
        public Builder completionToast(@Nullable CompletionToast value) {
            this.completionToast = value != null ? value : NO_COMPLETION_TOAST;
            return this;
        }

        @Nonnull
        public Builder completion(@Nullable CompletionHandOff value) {
            this.completion = value != null ? value : NO_HAND_OFF;
            return this;
        }

        @Nonnull
        public CommercePageDeps build() {
            return new CommercePageDeps(this);
        }
    }
}
