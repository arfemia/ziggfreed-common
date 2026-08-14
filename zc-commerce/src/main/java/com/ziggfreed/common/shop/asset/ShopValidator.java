package com.ziggfreed.common.shop.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WhereValidator;

/**
 * Audits folded shop content for the mistakes that produce NO error at runtime: an offer nobody can
 * ever see, a shelf nothing can fill, a price in a wallet that does not exist, a reward nothing pays
 * out. Every one of them ships as a storefront that quietly does not sell what its author read, which
 * is far harder to chase than a finding at load.
 *
 * <p>Findings are shared {@link Finding} values under domain {@code shop}, so a consumer folds them
 * into its own report beside every other validator's. Gate findings come from the SHARED
 * {@link GateValidator} and world-targeting findings from the shared {@link WhereValidator}, so a
 * lock or a selector is reported here exactly as it is on a quest.
 *
 * <p><b>Every unknown id is a WARNING, never an error</b> - the standing library rule. Whichever mod
 * owns a wallet, a reward kind or a factor registers it at its own setup, which may be a mod the
 * author expects some servers not to install. A thing that is impossible whatever anybody installs -
 * a shelf whose slots no offer can fill - is an error.
 */
public final class ShopValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "shop";

    /** What one piece of this content is CALLED in a message written for the author. */
    private static final String NOUN = "offer";

    /**
     * Answers "does this server define a wallet under this id?" - a probe rather than a lookup,
     * because which wallets exist is folded a layer up and a caller with no answer skips the check
     * rather than reporting every price as unknown.
     */
    @FunctionalInterface
    public interface CurrencyProbe {

        /** True when some layer defines a wallet under {@code currencyId}. */
        boolean defines(@Nonnull String currencyId);
    }

    private ShopValidator() {
    }

    /**
     * Audit a whole folded catalogue against the storefronts and shelves it names.
     *
     * <p>Every vocabulary is optional: pass null for one and the checks that depend on it are
     * skipped rather than reporting everything as unknown.
     *
     * @param entries      the folded offers, keyed by id
     * @param shops        the storefronts any layer defines, keyed by id
     * @param pools        the rotating shelves any layer defines, keyed by id
     * @param currencies   answers "does this wallet exist?", or null to skip
     * @param rewardKinds  answers "does anything pay this reward kind out?", or null to skip
     * @param gateKinds    the registered {@code Requires.Custom} vocabulary, or null to skip
     * @param knownFactors answers "does anything provide this factor id?", or null to skip
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull Map<String, ShopEntryAsset> entries,
            @Nonnull Map<String, ShopAsset> shops, @Nonnull Map<String, ShopPoolAsset> pools,
            @Nullable CurrencyProbe currencies, @Nullable Predicate<String> rewardKinds,
            @Nullable GateKindRegistry gateKinds, @Nullable Predicate<String> knownFactors) {

        List<Finding> out = new ArrayList<>();

        for (Map.Entry<String, ShopAsset> entry : shops.entrySet()) {
            if (entry.getValue() != null) {
                validateShop(entry.getKey(), entry.getValue(), currencies, gateKinds, knownFactors, out);
            }
        }
        for (Map.Entry<String, ShopPoolAsset> entry : pools.entrySet()) {
            if (entry.getValue() != null) {
                validatePool(entry.getKey(), entry.getValue(), shops, entries, currencies, out);
            }
        }
        for (Map.Entry<String, ShopEntryAsset> entry : entries.entrySet()) {
            if (entry.getValue() != null) {
                validateEntry(entry.getKey(), entry.getValue(), shops, pools, currencies, rewardKinds,
                        gateKinds, knownFactors, out);
            }
        }
        return out;
    }

    // ==================== storefronts ====================

    private static void validateShop(@Nonnull String id, @Nonnull ShopAsset shop,
            @Nullable CurrencyProbe currencies, @Nullable GateKindRegistry gateKinds,
            @Nullable Predicate<String> knownFactors, @Nonnull List<Finding> out) {

        if (shop.currencyIds().isEmpty()) {
            out.add(Finding.info(DOMAIN, "NO_HEADER_CURRENCIES",
                    "no Currencies are listed, so the header shows no balances at all and a player has to guess "
                            + "what they can afford; list every wallet this storefront prices in", id));
        }
        if (currencies != null) {
            for (String currencyId : shop.currencyIds()) {
                if (!currencies.defines(currencyId)) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_CURRENCY",
                            "the header lists the wallet '" + currencyId + "', which nothing defines; it shows "
                                    + "as nothing until whichever pack owns it is installed", id));
                }
            }
        }
        if (shop.getWhere() != null) {
            out.addAll(stamp(WhereValidator.validateSelector(shop.getWhere(), id + ".Where"), id));
        }
        out.addAll(GateValidator.validate(shop.getRequires(), DOMAIN, id, "storefront",
                gateKinds, knownFactors, null));
    }

    // ==================== shelves ====================

    private static void validatePool(@Nonnull String id, @Nonnull ShopPoolAsset pool,
            @Nonnull Map<String, ShopAsset> shops, @Nonnull Map<String, ShopEntryAsset> entries,
            @Nullable CurrencyProbe currencies, @Nonnull List<Finding> out) {

        String shopId = pool.getShop();
        if (shopId == null) {
            out.add(Finding.error(DOMAIN, "POOL_WITHOUT_SHOP",
                    "no Shop is named, so this shelf sits in no storefront and never appears anywhere", id));
        } else if (!shops.containsKey(shopId)) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_SHOP",
                    "Shop names '" + shopId + "', which nothing defines, so this shelf never appears; it comes "
                            + "back on its own if the pack owning that storefront is installed", id));
        }

        RotationAsset rotation = pool.getRotation();
        if (rotation != null) {
            validateRotation(rotation, id, out);
        }
        SelectionAsset selection = pool.getSelection();
        if (selection != null && selection.getType() != null && !isKnownSelection(selection.getType())) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_SELECTION",
                    "Selection.Type is '" + selection.getType() + "', which nothing registered; the shelf cannot "
                            + "draw until whichever mod owns that strategy is installed", id));
        }

        // Which offers could ever land on this shelf, counted by the tier its slots filter on.
        Map<String, Integer> byTier = new LinkedHashMap<>();
        int eligible = 0;
        for (ShopEntryAsset offer : entries.values()) {
            ShopEntryAsset.PoolMembership membership = offer == null ? null : offer.getPool();
            if (membership == null || !id.equals(membership.getId()) || !offer.isEnabled()) {
                continue;
            }
            eligible++;
            String tier = membership.getTier();
            if (tier != null) {
                byTier.merge(tier, 1, Integer::sum);
            }
        }
        if (eligible == 0) {
            out.add(Finding.error(DOMAIN, "EMPTY_POOL",
                    "no offer names this shelf under Pool.Id, so it is drawn from an empty set and shows "
                            + "nothing at all", id));
        }

        int required = 0;
        for (PoolSlotAsset slot : pool.slotsOrEmpty()) {
            if (slot == null) {
                continue;
            }
            Integer authoredCount = slot.getCount();
            if (authoredCount != null && authoredCount < 1) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_SLOT_COUNT",
                        "a slot asks for " + authoredCount + " offers, which is read as 1; drop the slot when "
                                + "you mean it to yield nothing", id));
            }
            if (!slot.isOptional()) {
                required += slot.countOrOne();
            }
            String tier = slot.label();
            if (tier == null) {
                continue;
            }
            int available = byTier.getOrDefault(tier, 0);
            if (available == 0) {
                out.add(Finding.error(DOMAIN, "UNFILLABLE_SLOT",
                        "a slot draws the tier '" + slot.getTier() + "', which no offer on this shelf carries "
                                + "under Pool.Tier; the slot can never be filled" + (slot.isOptional()
                                ? " and is quietly skipped every rotation" : " and leaves a visible gap"), id));
            } else if (available < slot.countOrOne()) {
                out.add(Finding.warning(DOMAIN, "OVERSUBSCRIBED_POOL",
                        "a slot wants " + slot.countOrOne() + " DISTINCT offers of tier '" + slot.getTier()
                                + "' but only " + available + " exist, so the shelf comes up short every "
                                + "rotation; author more offers at that tier or lower the count", id));
            }
        }
        if (required > 0 && eligible > 0 && eligible < required) {
            out.add(Finding.warning(DOMAIN, "OVERSUBSCRIBED_POOL",
                    "the slots want " + required + " distinct offers but only " + eligible + " name this shelf, "
                            + "so it comes up short every rotation", id));
        }

        RerollAsset reroll = pool.getReroll();
        if (reroll != null) {
            validateReroll(reroll, id, currencies, out);
        }
    }

    // ==================== offers ====================

    private static void validateEntry(@Nonnull String id, @Nonnull ShopEntryAsset offer,
            @Nonnull Map<String, ShopAsset> shops, @Nonnull Map<String, ShopPoolAsset> pools,
            @Nullable CurrencyProbe currencies, @Nullable Predicate<String> rewardKinds,
            @Nullable GateKindRegistry gateKinds, @Nullable Predicate<String> knownFactors,
            @Nonnull List<Finding> out) {

        String shopId = offer.getShop();
        if (shopId == null) {
            out.add(Finding.error(DOMAIN, "ENTRY_WITHOUT_SHOP",
                    "no Shop is named, so this offer is sold nowhere; name the storefront it belongs to", id));
        } else if (!shops.containsKey(shopId)) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_SHOP",
                    "Shop names '" + shopId + "', which nothing defines, so this offer is never on sale; it "
                            + "comes back on its own if the pack owning that storefront is installed", id));
        }

        ShopEntryAsset.PoolMembership membership = offer.getPool();
        if (membership != null) {
            String poolId = membership.getId();
            if (poolId == null) {
                out.add(Finding.warning(DOMAIN, "POOL_WITHOUT_ID",
                        "a Pool block is authored with no Id, so this offer belongs to no shelf and is never "
                                + "drawn; either name the shelf or drop the block to sell it always", id));
            } else if (!pools.containsKey(poolId)) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_POOL",
                        "Pool.Id names '" + poolId + "', which nothing defines, so this offer is never drawn "
                                + "onto anything", id));
            }
            Double weight = membership.getWeight();
            if (weight != null && weight <= 0.0) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_WEIGHT",
                        "Pool.Weight is " + weight + ", which would make the offer undrawable; it is read as 1. "
                                + "Take the offer off the shelf with Enabled instead", id));
            }
        }

        validateCost(offer.costOrFree(), id, "Cost", currencies, out);

        RewardEntryAsset[] rewards = offer.rewardsOrEmpty();
        if (rewards.length == 0) {
            out.add(Finding.error(DOMAIN, "EMPTY_REWARDS",
                    "the offer hands over nothing, so a player pays and receives no reward at all", id));
        }
        for (RewardEntryAsset reward : rewards) {
            if (reward == null || reward.isBlank()) {
                out.add(Finding.error(DOMAIN, "BLANK_REWARD",
                        "a Rewards entry names no Kind, so it can never pay anything out", id));
                continue;
            }
            String kind = reward.getKind();
            if (rewardKinds != null && kind != null && !rewardKinds.test(kind)) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_REWARD_KIND",
                        "the reward '" + kind + "' has no handler registered, so buying this pays out nothing "
                                + "for it", id));
            }
        }

        ShopEntryAsset.Limits limits = offer.getLimits();
        if (limits != null) {
            Integer daily = limits.getDaily();
            Integer total = limits.getTotal();
            if (daily != null && daily < 0) {
                out.add(Finding.warning(DOMAIN, "NEGATIVE_LIMIT",
                        "Limits.Daily is " + daily + ", which reads as no daily limit; use 0 to say unlimited",
                        id));
            }
            if (total != null && total < 0) {
                out.add(Finding.warning(DOMAIN, "NEGATIVE_LIMIT",
                        "Limits.Total is " + total + ", which reads as no lifetime limit; use 0 to say "
                                + "unlimited", id));
            }
            if (daily != null && total != null && daily > 0 && total > 0 && daily > total) {
                out.add(Finding.info(DOMAIN, "DAILY_ABOVE_TOTAL",
                        "Limits.Daily is " + daily + " but Limits.Total is " + total + ", so the lifetime cap "
                                + "runs out first and the daily allowance never bites", id));
            }
        }

        out.addAll(GateValidator.validate(offer.getRequires(), DOMAIN, id, NOUN,
                gateKinds, knownFactors, null));
    }

    // ==================== shared pieces ====================

    /** A rotation's own shape: the one pairing that cannot be resolved, and the words it knows. */
    private static void validateRotation(@Nonnull RotationAsset rotation, @Nonnull String id,
            @Nonnull List<Finding> out) {

        if (rotation.hasBothCadences()) {
            out.add(Finding.error(DOMAIN, "BOTH_PERIOD_AND_EVERY",
                    "Rotation authors BOTH Period and Every; they are two ways of saying when the set turns "
                            + "over and only one of them can apply. Keep the one you meant", id));
        }
        if (rotation.hasUnknownPeriod()) {
            out.add(Finding.error(DOMAIN, "UNKNOWN_PERIOD",
                    "Rotation.Period is '" + rotation.getPeriod() + "', which is neither "
                            + RotationAsset.PERIOD_DAILY + " nor " + RotationAsset.PERIOD_WEEKLY
                            + "; write a span under Every for any other cadence", id));
        }
        if (rotation.parsedWeekday() == null) {
            out.add(Finding.error(DOMAIN, "UNKNOWN_WEEKDAY",
                    "Rotation.Weekday is '" + rotation.getWeekday() + "', which is not a day name; the set "
                            + "turns over on Monday instead", id));
        } else if (rotation.getWeekday() != null && !rotation.isWeekly()) {
            out.add(Finding.warning(DOMAIN, "WEEKDAY_WITHOUT_WEEKLY",
                    "Rotation.Weekday does nothing unless Period is " + RotationAsset.PERIOD_WEEKLY
                            + "; either drop it or make the cadence weekly", id));
        }
        Integer offset = rotation.getOffsetMinutes();
        if (offset != null && offset < 0) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_OFFSET",
                    "Rotation.OffsetMinutes is " + offset + ", which is read as none; a rollover cannot happen "
                            + "before its own boundary", id));
        }
    }

    /** A reroll block: the price has to be payable, and an endless free reroll is worth saying. */
    private static void validateReroll(@Nonnull RerollAsset reroll, @Nonnull String id,
            @Nullable CurrencyProbe currencies, @Nonnull List<Finding> out) {

        CostAsset cost = reroll.getCost();
        if (cost != null) {
            validateCost(cost, id, "Reroll.Cost", currencies, out);
        }
        if ((cost == null || cost.isFree()) && reroll.maxPerPeriod() <= 0) {
            out.add(Finding.warning(DOMAIN, "UNLIMITED_FREE_REROLL",
                    "a Reroll block is authored with no price and no MaxPerPeriod, so a player may reroll for "
                            + "ever until they get what they want, which makes the rotation itself pointless; "
                            + "author a price, a limit, or both", id));
        }
        Integer max = reroll.getMaxPerPeriod();
        if (max != null && max < 0) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_REROLL_LIMIT",
                    "Reroll.MaxPerPeriod is " + max + ", which reads as unlimited; use 0 to say unlimited", id));
        }
    }

    /** A price: the wallets have to exist, the amounts have to be worth charging. */
    private static void validateCost(@Nonnull CostAsset cost, @Nonnull String id, @Nonnull String field,
            @Nullable CurrencyProbe currencies, @Nonnull List<Finding> out) {

        Map<String, Long> authored = cost.getCurrencies();
        if (authored != null) {
            for (Map.Entry<String, Long> entry : authored.entrySet()) {
                String currencyId = entry.getKey();
                Long amount = entry.getValue();
                if (currencyId == null || currencyId.isBlank()) {
                    out.add(Finding.warning(DOMAIN, "BLANK_CURRENCY",
                            field + " carries an entry with no wallet id, so nothing is charged for it", id));
                    continue;
                }
                if (amount == null || amount <= 0L) {
                    out.add(Finding.warning(DOMAIN, "NON_POSITIVE_COST",
                            field + " charges " + amount + " of '" + currencyId + "', which costs the player "
                                    + "nothing; drop the entry when you mean it to be free", id));
                }
                if (currencies != null && !currencies.defines(currencyId.trim().toLowerCase(Locale.ROOT))) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_CURRENCY",
                            field + " is priced in the wallet '" + currencyId + "', which nothing defines; "
                                    + "nobody can hold it, so the price can never be met until whichever pack "
                                    + "owns it is installed", id));
                }
            }
        }
        for (CostAsset.ItemCostAsset item : cost.itemCosts()) {
            Integer count = item.getCount();
            if (count != null && count < 1) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_COST",
                        field + " takes " + count + " of '" + item.getItem() + "', which is read as 1", id));
            }
        }
        if (cost.hasUnknownCombine()) {
            out.add(Finding.error(DOMAIN, "UNKNOWN_COMBINE",
                    field + ".Combine is '" + cost.getCombine() + "', which is neither " + CostAsset.COMBINE_ALL
                            + " nor " + CostAsset.COMBINE_ANY + "; every component is charged instead, which "
                            + "may be several times what you meant", id));
        }
    }

    /** Is {@code type} one of the strategies this library seeds? */
    private static boolean isKnownSelection(@Nonnull String type) {
        return SelectionAsset.TYPE_WEIGHTED_RANDOM.equalsIgnoreCase(type)
                || SelectionAsset.TYPE_ALL.equalsIgnoreCase(type);
    }

    /** Re-file another validator's findings under this domain and this content id. */
    @Nonnull
    private static List<Finding> stamp(@Nonnull List<Finding> findings, @Nonnull String sourceId) {
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            out.add(new Finding(finding.severity(), finding.code(), finding.message(), sourceId, DOMAIN));
        }
        return out;
    }
}
