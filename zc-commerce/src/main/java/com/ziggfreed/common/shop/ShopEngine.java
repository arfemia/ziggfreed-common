package com.ziggfreed.common.shop;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.PeriodMath;
import com.ziggfreed.common.util.SafeLog;

/**
 * Buying something: what stops it, and the one order it happens in.
 *
 * <p><b>The engine's whole job is ORDERING and transactionality.</b> Every step is somebody else's
 * authority, asked in the one sequence that cannot leave a buyer short:
 *
 * <ol>
 *   <li><b>gate</b> - the offer is enabled, and the shared {@link GateEvaluator} passes its
 *       {@code Requires} block. Requirements are factors everywhere, evaluated by the same
 *       machinery a quest accept uses, so a shop lock and a quest lock mean the same thing.</li>
 *   <li><b>limits</b> - the commerce store's counts against the offer's own
 *       {@link PurchaseLimits}, using ONE day number threaded through the whole purchase.</li>
 *   <li><b>afford</b> - {@link CostEngine#check}, naming the first shortfall.</li>
 *   <li><b>room</b> - ONE batch fit probe over the whole reward list
 *       ({@link LootRewardKinds#canAddAll}). Asking per reward asks each about the same last free
 *       slot; asking once is what makes "come back with room" honest.</li>
 *   <li><b>drain</b> - {@link CostEngine#drain}, which answers a receipt of what it took.</li>
 *   <li><b>grant</b> - ONE call to {@link RewardGrants#grantAll}, the library's single issuance
 *       pass over the shared kind table. There is no grant loop here and nothing switches on a
 *       reward kind.</li>
 *   <li><b>refund</b> - if NOTHING was deliverable, the receipt goes back and the purchase does not
 *       count. A buyer can never pay for zero value.</li>
 *   <li><b>record</b> - the purchase lands on the store against the same day number step two
 *       read.</li>
 * </ol>
 *
 * <p><b>A refusal is a TOKEN, never a sentence.</b> Turning {@code "limit:daily"} or
 * {@code "cost:currency:bounty_token"} into words a player reads is the consumer's job, because
 * only the consumer knows their language and its own wording. A gate refusal is passed through
 * exactly as the shared evaluator worded it.
 */
public final class ShopEngine {

    /** The offer is switched off. */
    public static final String REASON_DISABLED = "disabled";

    /** Nothing answers to that offer id. */
    public static final String REASON_UNKNOWN_OFFER = "unknown_offer";

    /** Already bought as many times today as the offer allows. */
    public static final String REASON_LIMIT_DAILY = "limit:daily";

    /** Already bought as many times as the offer will ever allow. */
    public static final String REASON_LIMIT_TOTAL = "limit:total";

    /** Prefix of the token naming the currency the buyer is short of. */
    public static final String REASON_SHORT_CURRENCY = "cost:currency:";

    /** Prefix of the token naming the item the buyer is short of. */
    public static final String REASON_SHORT_ITEM = "cost:item:";

    /** The rewards would not fit in the buyer's inventory. */
    public static final String REASON_NO_ROOM = "no_room";

    /** The price could not be taken after all, so nothing was bought. */
    public static final String REASON_CANNOT_PAY = "cannot_pay";

    /** Every reward failed and could not be queued, so the price was given back. */
    public static final String REASON_REFUNDED = "refunded";

    /** Whether a purchase may go ahead, and what stopped it if not. */
    public record PurchaseCheck(boolean ok, @Nullable String reason) {

        /** Nothing is in the way. */
        public static final PurchaseCheck OK = new PurchaseCheck(true, null);

        /** Refused, naming what refused it. */
        @Nonnull
        public static PurchaseCheck refused(@Nonnull String reason) {
            return new PurchaseCheck(false, reason);
        }
    }

    /**
     * What happened. {@code grants} is null when the purchase never got as far as paying out; when
     * it is not, its three counts are the only record of what reached the buyer, what is waiting
     * for their next connect, and what was lost.
     */
    public record PurchaseOutcome(boolean ok, @Nullable String reason,
            @Nullable RewardGrants.GrantOutcome grants, @Nonnull Cost charged) {

        /** Refused before anything was charged. */
        @Nonnull
        public static PurchaseOutcome refused(@Nonnull String reason) {
            return new PurchaseOutcome(false, reason, null, Cost.FREE);
        }

        /** True when some rewards are waiting for the buyer's next connect. */
        public boolean anyQueued() {
            return grants != null && grants.queued() > 0;
        }
    }

    private final ShopCatalog catalog;
    private final CostEngine costs;
    private final GateEvaluator gates;
    private final Supplier<RewardKindRegistry> kinds;
    private final Supplier<CommerceStore> store;
    @Nullable private final BiConsumer<Subject, String> retryQueue;
    private final Consumer<String> warn;
    private final Consumer<String> info;

    private ShopEngine(@Nonnull Builder b) {
        this.catalog = b.catalog;
        this.costs = b.costs;
        this.gates = b.gates;
        this.kinds = b.kinds;
        this.store = b.store;
        this.retryQueue = b.retryQueue;
        this.warn = b.warn;
        this.info = b.info;
    }

    /** The offers this engine sells. */
    @Nonnull
    public ShopCatalog catalog() {
        return catalog;
    }

    /** The price authority every purchase charges through. */
    @Nonnull
    public CostEngine costs() {
        return costs;
    }

    /** The day number an instant belongs to, which every limit is counted against. */
    public static long epochDay(long nowMs) {
        return Math.floorDiv(nowMs, PeriodMath.DAY_MS);
    }

    // ==================== Check ====================

    /**
     * May {@code subject} buy {@code offer} right now? Every step of the purchase that can refuse,
     * asked without charging or granting anything, in the same order the purchase asks them.
     *
     * @param nowMs the clock, injected so a check and the purchase that follows agree about which
     *              day it is
     */
    @Nonnull
    public PurchaseCheck canPurchase(@Nonnull Subject subject, @Nonnull ShopOffer offer, long nowMs) {
        if (!offer.enabled()) {
            return PurchaseCheck.refused(REASON_DISABLED);
        }
        String gateFailure = gates.firstFailure(subject, offer.requires());
        if (gateFailure != null) {
            return PurchaseCheck.refused(gateFailure);
        }
        PurchaseCheck limits = checkLimits(subject, offer, epochDay(nowMs));
        if (!limits.ok()) {
            return limits;
        }
        Cost price = priceFor(subject, offer);
        CostEngine.Affordability afford = costs.check(subject, price);
        if (!afford.ok()) {
            return PurchaseCheck.refused(shortfallReason(afford));
        }
        if (!LootRewardKinds.canAddAll(offer.rewards(), subject)) {
            return PurchaseCheck.refused(REASON_NO_ROOM);
        }
        return PurchaseCheck.OK;
    }

    /** The limits half on its own, for a listing that greys out what is already spent. */
    @Nonnull
    public PurchaseCheck checkLimits(@Nonnull Subject subject, @Nonnull ShopOffer offer, long epochDay) {
        PurchaseLimits limits = offer.limits();
        if (limits == null || limits.isOpen()) {
            return PurchaseCheck.OK;
        }
        CommerceStore state = store.get();
        if (!state.recordsPurchases()) {
            // The store cannot count, so the limits cannot hold. Say so rather than pretending.
            warn.accept("[shop] '" + offer.offerId() + "' authors purchase limits but this server's "
                    + "commerce store keeps no counts, so they do not apply");
            return PurchaseCheck.OK;
        }
        if (limits.totalReached(state.purchasesTotal(subject, offer.offerId()))) {
            return PurchaseCheck.refused(REASON_LIMIT_TOTAL);
        }
        if (limits.dailyReached(state.purchasesToday(subject, offer.offerId(), epochDay))) {
            return PurchaseCheck.refused(REASON_LIMIT_DAILY);
        }
        return PurchaseCheck.OK;
    }

    /**
     * What this subject would pay for this offer right now: the authored price grown by however
     * many they have already bought. The ONE place scaling is applied, so a chip a buyer reads and
     * the amount they are charged cannot differ.
     */
    @Nonnull
    public Cost priceFor(@Nonnull Subject subject, @Nonnull ShopOffer offer) {
        Cost authored = offer.cost();
        if (authored.scaling() == null) {
            return authored;
        }
        return authored.scaled(store.get().purchasesTotal(subject, offer.offerId()));
    }

    // ==================== Purchase ====================

    /** {@link #purchase(Subject, ShopOffer, long)} for an offer named by id. */
    @Nonnull
    public PurchaseOutcome purchase(@Nonnull Subject subject, @Nonnull String offerId, long nowMs) {
        ShopOffer offer = catalog.offer(offerId);
        if (offer == null) {
            return PurchaseOutcome.refused(REASON_UNKNOWN_OFFER);
        }
        return purchase(subject, offer, nowMs);
    }

    /**
     * Run the whole purchase. Charges nothing when it refuses, and gives back what it charged when
     * nothing at all could be delivered.
     *
     * @param nowMs the clock, injected: ONE day number is read from it and threaded through the
     *              limit check and the record, so a purchase spanning midnight cannot check
     *              yesterday's count and record against today's
     */
    @Nonnull
    public PurchaseOutcome purchase(@Nonnull Subject subject, @Nonnull ShopOffer offer, long nowMs) {
        long day = epochDay(nowMs);
        PurchaseCheck check = canPurchase(subject, offer, nowMs);
        if (!check.ok()) {
            return PurchaseOutcome.refused(check.reason() == null ? REASON_CANNOT_PAY : check.reason());
        }

        Cost price = priceFor(subject, offer);
        CostEngine.Receipt receipt = costs.drain(subject, price);
        if (!receipt.ok()) {
            return PurchaseOutcome.refused(REASON_CANNOT_PAY);
        }

        String sourceId = "shop:" + offer.offerId();
        List<RewardSpec> rewards = offer.rewards();
        RewardGrants.GrantOutcome grants =
                RewardGrants.grantAll(rewards, subject, sourceId, kinds.get(), retryQueue, warn);

        if (!rewards.isEmpty() && !grants.anyDelivered()) {
            costs.refund(subject, receipt);
            warn.accept("[shop] " + sourceId + ": every reward failed, so the price was refunded");
            return new PurchaseOutcome(false, REASON_REFUNDED, grants, Cost.FREE);
        }

        store.get().recordPurchase(subject, offer.offerId(), day);
        info.accept("[shop] " + sourceId + ": granted=" + grants.granted()
                + " queued=" + grants.queued() + " failed=" + grants.failed());
        return new PurchaseOutcome(true, null, grants, receipt.paid());
    }

    @Nonnull
    private static String shortfallReason(@Nonnull CostEngine.Affordability afford) {
        if (afford.shortCurrencyId() != null) {
            return REASON_SHORT_CURRENCY + afford.shortCurrencyId();
        }
        if (afford.shortItemId() != null) {
            return REASON_SHORT_ITEM + afford.shortItemId();
        }
        return REASON_CANNOT_PAY;
    }

    @Nonnull
    public static Builder builder(@Nonnull CostEngine costs, @Nonnull GateEvaluator gates) {
        return new Builder(costs, gates);
    }

    /** Assembles a {@link ShopEngine}; every seam but the price and gate authorities has a default. */
    public static final class Builder {

        private final CostEngine costs;
        private final GateEvaluator gates;
        private ShopCatalog catalog = ShopCatalog.EMPTY;
        private Supplier<RewardKindRegistry> kinds = RewardKinds::shared;
        private Supplier<CommerceStore> store = CommerceStores::get;
        @Nullable private BiConsumer<Subject, String> retryQueue;
        private Consumer<String> warn = SafeLog::warn;
        private Consumer<String> info = SafeLog::info;

        private Builder(@Nonnull CostEngine costs, @Nonnull GateEvaluator gates) {
            this.costs = costs;
            this.gates = gates;
        }

        /** Which offers exist. Unset means none. */
        @Nonnull
        public Builder catalog(@Nonnull ShopCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        /**
         * The reward vocabulary a payout is looked up in. Defaults to the shared table, which is
         * where a consumer registers its kinds, so most callers never set this.
         */
        @Nonnull
        public Builder kinds(@Nonnull Supplier<RewardKindRegistry> kinds) {
            this.kinds = kinds;
            return this;
        }

        /** A fixed reward vocabulary, for a test holding its own table. */
        @Nonnull
        public Builder kinds(@Nonnull RewardKindRegistry kinds) {
            this.kinds = () -> kinds;
            return this;
        }

        /** Where purchase counts live. Defaults to whatever is installed at call time. */
        @Nonnull
        public Builder store(@Nonnull Supplier<CommerceStore> store) {
            this.store = store;
            return this;
        }

        /** A fixed store, for a test that wants to hold the instance it drives. */
        @Nonnull
        public Builder store(@Nonnull CommerceStore store) {
            this.store = () -> store;
            return this;
        }

        /**
         * Where a reward that failed but is replayable is queued for the buyer's next connect.
         * Unset means there is nowhere to queue, so such a failure is reported and lost.
         */
        @Nonnull
        public Builder retryQueue(@Nullable BiConsumer<Subject, String> retryQueue) {
            this.retryQueue = retryQueue;
            return this;
        }

        /** Where a refund and a lost reward are reported. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        /** Where a completed purchase is noted. */
        @Nonnull
        public Builder info(@Nonnull Consumer<String> info) {
            this.info = info;
            return this;
        }

        @Nonnull
        public ShopEngine build() {
            return new ShopEngine(this);
        }
    }
}
