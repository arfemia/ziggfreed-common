package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One generic, mod-agnostic reward descriptor: an {@link Kind} + an id + a quantity +
 * an optional display lang key. Common ships the MODEL only - the consumer's grant
 * executor interprets it (an {@code ITEM} into an inventory add, a {@code CURRENCY} into
 * its currency service, a {@code COMMAND} into a console run), so common imports no
 * reward engine. The results screen renders each as a reward chip.
 *
 * <p>Pack-authored as a compact space-delimited spec string (the
 * {@code Rewards: String[]} field on an {@code InstancePresetAsset}, since the codec
 * has no list-of-objects form), Java-authored via the {@link #item}/{@link #currency}/
 * {@link #command} factories (the API-overridable path). Spec grammar (ids never
 * contain spaces):
 * <pre>
 *   item     &lt;itemId&gt;     &lt;quantity&gt; [displayKey]
 *   currency &lt;currencyId&gt; &lt;amount&gt;   [displayKey]
 * </pre>
 * Commands (which contain spaces) are Java-authored only, not pack-spec'd.
 */
public record InstanceReward(@Nonnull Kind kind, @Nonnull String id, int quantity, @Nullable String displayKey) {

    public enum Kind {
        /** An item stack: {@code id} = item id, {@code quantity} = count. Subject to the inventory-space guard. */
        ITEM,
        /** A currency / token: {@code id} = currency id, {@code quantity} = amount. No space guard. */
        CURRENCY,
        /** A console command template: {@code id} = the command (may contain a {player} placeholder). No space guard. */
        COMMAND
    }

    public InstanceReward {
        quantity = Math.max(1, quantity);
    }

    @Nonnull
    public static InstanceReward item(@Nonnull String itemId, int quantity, @Nullable String displayKey) {
        return new InstanceReward(Kind.ITEM, itemId, quantity, displayKey);
    }

    @Nonnull
    public static InstanceReward currency(@Nonnull String currencyId, int amount, @Nullable String displayKey) {
        return new InstanceReward(Kind.CURRENCY, currencyId, amount, displayKey);
    }

    @Nonnull
    public static InstanceReward command(@Nonnull String command, @Nullable String displayKey) {
        return new InstanceReward(Kind.COMMAND, command, 1, displayKey);
    }

    /** True for an {@link Kind#ITEM} reward - the only kind the inventory-space guard applies to. */
    public boolean isItem() {
        return kind == Kind.ITEM;
    }

    /**
     * Parse one compact spec (see the class doc). Returns {@code null} for a blank,
     * malformed, or unknown-kind spec (the caller skips it).
     */
    @Nullable
    public static InstanceReward parse(@Nullable String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] p = spec.trim().split("\\s+");
        if (p.length < 3) {
            return null;
        }
        int qty;
        try {
            qty = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        String displayKey = p.length >= 4 ? p[3] : null;
        return switch (p[0].toLowerCase(Locale.ROOT)) {
            case "item" -> item(p[1], qty, displayKey);
            case "currency" -> currency(p[1], qty, displayKey);
            default -> null;
        };
    }

    /** Parse a spec array into a reward list, skipping any malformed entries. */
    @Nonnull
    public static List<InstanceReward> parseAll(@Nullable String[] specs) {
        List<InstanceReward> out = new ArrayList<>();
        if (specs == null) {
            return out;
        }
        for (String spec : specs) {
            InstanceReward r = parse(spec);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
