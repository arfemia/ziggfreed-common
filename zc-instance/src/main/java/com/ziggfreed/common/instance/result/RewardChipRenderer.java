package com.ziggfreed.common.instance.result;

import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * Builds a {@link RewardChip} for an {@link InstanceReward}, AUTO-GENERATING the chip label so a
 * pack author no longer needs a hand-authored {@code displayKey}.
 *
 * <p>The chip it hands back is the shared {@link RewardChip} every payout surface in the library
 * paints - a quest panel, a storefront offer, a board contract, this results strip - so one reward
 * cannot read one way on a spoils screen and another way everywhere else. An {@link InstanceReward}
 * names its payout as an id plus a quantity, so the label is read in this order:
 *
 * <ol>
 *   <li>the row's own {@code displayKey}, when a {@code displayKeyResolver} is supplied (the
 *       consumer's {@code Lang}, e.g. {@code (key, qty) -> Lang.msg(key).param("0", qty)}) and the key
 *       is the reward's own {@code NameKey} or one something on this server ships;</li>
 *   <li>the shared reward reading ({@link RewardChips#chipFor(RewardSpec)}) of the reward the row
 *       stands for: the spec a deferred row kept, its amount at the row's own quantity so a half
 *       Claim reads half; else, for a currency row, a {@code Currency} reward of its id and quantity;
 *       else, for a command row, a {@code Command} reward running its line. That reading names an
 *       item, a kind file's {@code Presentation}, or what a kind's owner contributed (a wallet reads
 *       "+10 Sweets" with its own icon);</li>
 *   <li>an item's own engine-declared display name via {@link ItemStack#getDisplayName()}, a
 *       client-resolved {@link Message} in the player's own locale;</li>
 *   <li>one generic line ({@link #GENERIC_KEY}, "x10 Reward"), never the row's id: a command row's
 *       id is the console line that pays it.</li>
 * </ol>
 */
public final class RewardChipRenderer {

    /**
     * The line a reward nothing else names reads as, {@code {0}} its count as a typed number: shipped in
     * this module's {@code ziggfreedcommon.claims.lang}.
     */
    public static final String GENERIC_KEY = "ziggfreedcommon.claims.chip.generic";

    /** The wallet reward kind and its wallet parameter, zc-commerce's {@code CurrencyRewardKind}, out of reach here. */
    private static final String CURRENCY_KIND = "Currency";
    private static final String CURRENCY_PARAM = "Currency";

    private RewardChipRenderer() {
    }

    /**
     * The chip for {@code reward}, read in the order the class names: its authored label, the reward it
     * stands for, an item's own name, then the generic line.
     */
    @Nonnull
    public static RewardChip toChip(@Nonnull InstanceReward reward,
                                    @Nullable BiFunction<String, Integer, Message> displayKeyResolver) {
        if (displayKeyResolver != null && paintsDisplayKey(reward)) {
            return RewardChip.of(iconOf(reward), displayKeyResolver.apply(reward.displayKey(), reward.quantity()));
        }
        RewardChip read = readChip(reward);
        return read != null ? read : RewardChip.of(iconOf(reward), autoLabel(reward));
    }

    /**
     * Whether {@code reward}'s {@code displayKey} is the label to paint: the reward's own {@code NameKey} as
     * written (an author's word stays even when nothing ships it, so a typo shows as a key the author can
     * trace), else only a key something on this server ships. A key a kind file's {@code Presentation}
     * template filled for a value no file names is a default pointing at nothing, so it falls through to the
     * reading below it, as {@link RewardChips#chipFor(RewardSpec)} treats the same key.
     */
    private static boolean paintsDisplayKey(@Nonnull InstanceReward reward) {
        String key = reward.displayKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        RewardSpec spec = reward.spec();
        return spec != null && key.equals(spec.param(DeferredRewards.PARAM_NAME_KEY)) || ContentKeys.known(key);
    }

    /**
     * True when {@code reward} pays into a wallet: a currency row, or a row deferred from a {@code Currency}
     * reward (its kind matched in any case). A Claim of wallets alone has no item to picture, so it wears its
     * first chip's picture, the wallet's own.
     */
    public static boolean isWallet(@Nonnull InstanceReward reward) {
        RewardSpec spec = reward.spec();
        return reward.kind() == InstanceReward.Kind.CURRENCY
                || spec != null && CURRENCY_KIND.equalsIgnoreCase(spec.kind());
    }

    /**
     * The label with nothing authored to read: "x{qty} {ItemName}" for an item (qty &gt; 1) or the bare
     * item name for a single one, else the generic line ({@link #genericLabel}). The item name is a
     * client-resolved {@link Message}; the "x{qty} " prefix is locale-neutral quantity glue. Never the
     * reward's id.
     */
    @Nonnull
    public static Message autoLabel(@Nonnull InstanceReward reward) {
        int qty = reward.quantity();
        if (reward.isItem()) {
            Message name = new ItemStack(reward.id(), Math.max(1, qty)).getDisplayName();
            return qty > 1 ? Message.join(Message.raw("x" + qty + " "), name) : name;
        }
        return genericLabel(qty);
    }

    /** The generic line for a reward of {@code quantity}: "x10 Reward", the count bound as a typed number. */
    @Nonnull
    public static Message genericLabel(int quantity) {
        return Msg.key(GENERIC_KEY, Math.max(1, quantity));
    }

    /** The shared reading of the reward {@code reward} stands for, or null when it names nothing. */
    @Nullable
    private static RewardChip readChip(@Nonnull InstanceReward reward) {
        RewardSpec spec = readingSpec(reward);
        if (spec == null) {
            return null;
        }
        try {
            return RewardChips.chipFor(spec);
        } catch (Throwable t) {
            // A reading that fails costs this chip its name, never the screen.
            return null;
        }
    }

    /**
     * The reward {@code reward} stands for, at its own quantity: the spec it was deferred from, else a
     * {@code Currency} reward for a currency row or a {@code Command} reward for a command row (its
     * {@code {amount}} filled, as the payout fills it), carrying the row's own icon as its {@code Icon};
     * null for an item row with no spec, which its own name reads. Only an amount the quantity follows is
     * replaced: a spec whose amount the row could not show (a loss, deferred at one) keeps its own.
     */
    @Nullable
    private static RewardSpec readingSpec(@Nonnull InstanceReward reward) {
        String quantity = Integer.toString(reward.quantity());
        RewardSpec spec = reward.spec();
        if (spec != null) {
            String amount = DeferredRewards.quantityParam(spec);
            return amount == null || spec.longParam(amount, 0L) <= 0L ? spec : spec.with(amount, quantity);
        }
        RewardSpec built = switch (reward.kind()) {
            case CURRENCY -> RewardSpec.of(CURRENCY_KIND,
                    Map.of(CURRENCY_PARAM, reward.id(), DeferredRewards.PARAM_AMOUNT, quantity));
            case COMMAND -> RewardSpec.of(LootRewardKinds.KIND_COMMAND, LootRewardKinds.P_COMMAND,
                    reward.id().replace(InstanceReward.AMOUNT_PLACEHOLDER, quantity));
            case ITEM -> null;
        };
        String icon = reward.iconItemId();
        return built == null || icon == null || icon.isBlank() ? built : built.with(DeferredRewards.PARAM_ICON, icon);
    }

    /** An explicit {@code iconItemId} (a registered token's icon) wins; else an item chip uses its own id. */
    @Nullable
    private static String iconOf(@Nonnull InstanceReward reward) {
        return reward.iconItemId() != null ? reward.iconItemId() : (reward.isItem() ? reward.id() : null);
    }
}
