package com.ziggfreed.common.instance.result;

import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import com.ziggfreed.common.instance.reward.InstanceReward;

/**
 * Builds a results-screen {@link RewardChip} for an {@link InstanceReward}, AUTO-GENERATING the
 * chip label so a pack author no longer needs a hand-authored {@code displayKey}.
 *
 * <p><b>Mod-agnostic naming.</b> An item's label is the item's own engine-declared display name
 * via {@link ItemStack#getDisplayName()} - a client-resolved {@link Message} that resolves
 * whatever name key the item asset declares (vanilla {@code items.<id>.name} AND a custom item's
 * own {@code TranslationProperties.Name}). Common therefore guesses NO key convention and ships
 * NO {@code .lang}; the client renders the name in its own locale. A currency / command reward
 * (no backing item name) falls back to its id.
 *
 * <p>{@code displayKey} stays an OPTIONAL override: when it is set and a {@code displayKeyResolver}
 * is supplied (the consumer's {@code Lang}, e.g. {@code (key, qty) -> Lang.msg(key).param("0", qty)}),
 * that authored label wins; otherwise the label auto-generates.
 */
public final class RewardChipRenderer {

    private RewardChipRenderer() {
    }

    /**
     * The chip for {@code reward}: an item-icon chip (icon = the item id) with the auto / authored
     * label, or an icon-less chip for a currency / command reward. {@code pending} marks a reward
     * blocked by a full inventory (the page tints it).
     */
    @Nonnull
    public static RewardChip toChip(@Nonnull InstanceReward reward, boolean pending,
                                    @Nullable BiFunction<String, Integer, Message> displayKeyResolver) {
        Message label = reward.displayKey() != null && displayKeyResolver != null
                ? displayKeyResolver.apply(reward.displayKey(), reward.quantity())
                : autoLabel(reward);
        return new RewardChip(reward.isItem() ? reward.id() : null, label, pending);
    }

    /**
     * The auto-generated label: "x{qty} {ItemName}" for an item (qty &gt; 1), the bare item name for
     * a single item, or "x{qty} {id}" for a currency / command reward. The item name is a
     * client-resolved {@link Message}; the "x{qty} " prefix is locale-neutral quantity glue.
     */
    @Nonnull
    public static Message autoLabel(@Nonnull InstanceReward reward) {
        int qty = reward.quantity();
        if (reward.isItem()) {
            Message name = new ItemStack(reward.id(), Math.max(1, qty)).getDisplayName();
            return qty > 1 ? Message.join(Message.raw("x" + qty + " "), name) : name;
        }
        return Message.raw("x" + qty + " " + reward.id());
    }
}
