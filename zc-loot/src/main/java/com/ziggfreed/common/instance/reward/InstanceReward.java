package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardAuthoring;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;

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
 * A consumer can contribute EXTRA kind tokens by registering a {@link RewardAuthoring} adapter
 * on the shared {@link RewardKinds#shared()} vocabulary (e.g. an {@code xp} token that expands
 * to a {@code command} spec); those parse positionally the same way, with the token's
 * {@code arg} expanded into the reward id (a space-bearing command template) and an optional
 * {@code icon} parameter - so common needs no {@code command} spec case and stays domain-free.
 * A raw command is still Java-authored via {@link #command}.
 *
 * <p>An adapter expands to a {@link RewardSpec} whose own kind names one of {@link Kind}
 * (case-insensitively) and whose {@code id} parameter is the reward id; an {@code icon}
 * parameter, when present, becomes the results chip's art. A token nobody registered does not
 * parse and the line is skipped, which is what keeps a spec written for an absent mod from
 * paying out something wrong.
 *
 * <p>{@link #iconItemId} is an OPTIONAL results-chip icon (an item id). It is null for a plain
 * item/currency spec (an item chip derives its icon from its own id); a registered token supplies
 * it via its icon resolver so a non-item reward (e.g. an XP command) can still show art.
 */
public record InstanceReward(@Nonnull Kind kind, @Nonnull String id, int quantity, @Nullable String displayKey,
                             @Nullable String iconItemId) {

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
        return new InstanceReward(Kind.ITEM, itemId, quantity, displayKey, null);
    }

    @Nonnull
    public static InstanceReward currency(@Nonnull String currencyId, int amount, @Nullable String displayKey) {
        return new InstanceReward(Kind.CURRENCY, currencyId, amount, displayKey, null);
    }

    @Nonnull
    public static InstanceReward command(@Nonnull String command, @Nullable String displayKey) {
        return new InstanceReward(Kind.COMMAND, command, 1, displayKey, null);
    }

    /**
     * A console-command reward carrying a display {@code quantity} + an optional chip {@code iconItemId}.
     * The quantity is not an item count here - it is the amount the granter substitutes for a
     * {@code {amount}} placeholder in the command template AND the number the results chip renders
     * (so an XP command reads "+{amount} ..." on the chip).
     */
    @Nonnull
    public static InstanceReward command(@Nonnull String command, int quantity, @Nullable String displayKey,
                                         @Nullable String iconItemId) {
        return new InstanceReward(Kind.COMMAND, command, quantity, displayKey, iconItemId);
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
        String token = p[0].toLowerCase(Locale.ROOT);
        switch (token) {
            case "item":
                return item(p[1], qty, displayKey);
            case "currency":
                return currency(p[1], qty, displayKey);
            default:
                return fromExpandedToken(token, p[1], qty, displayKey);
        }
    }

    /**
     * One consumer-registered token expanded through the shared reward vocabulary. Null when nothing
     * is registered for the token, when the adapter refused the argument, or when what it expanded to
     * names no {@link Kind} this record can carry.
     */
    @Nullable
    static InstanceReward fromExpandedToken(@Nonnull String token, @Nonnull String arg, int qty,
                                            @Nullable String displayKey) {
        RewardSpec expanded = RewardKinds.shared().expand(token, arg);
        if (expanded == null) {
            return null;
        }
        Kind kind = kindOf(expanded.kind());
        String id = expanded.param("id");
        if (kind == null || id == null || id.isBlank()) {
            return null;
        }
        return new InstanceReward(kind, id, qty, displayKey, expanded.param("icon"));
    }

    /** A spec's kind name read as one of {@link Kind}, or null when it names none of them. */
    @Nullable
    static Kind kindOf(@Nonnull String kindName) {
        try {
            return Kind.valueOf(kindName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Combine same-identity rewards into one entry each, summing quantities, so the results screen
     * shows "x9 Moonbloom" ONCE instead of four separate Moonbloom chips (and the claim store grants
     * one merged entry instead of several). Two rewards merge when their {@link #kind}, {@link #id},
     * and {@link #displayKey} all match. {@link Kind#COMMAND} entries NEVER merge - each command is a
     * distinct side effect, and summing a quantity would be meaningless. First-seen order is preserved
     * (a merged entry keeps the slot of its first occurrence). Mod-agnostic: any consumer rolling a
     * loot table (which intentionally yields repeated picks) routes its result through here.
     */
    @Nonnull
    public static List<InstanceReward> merge(@Nonnull List<InstanceReward> rewards) {
        List<InstanceReward> out = new ArrayList<>(rewards.size());
        Map<String, Integer> slotByKey = new HashMap<>(); // merge key -> index into out
        for (InstanceReward r : rewards) {
            if (r.kind == Kind.COMMAND) {
                out.add(r);
                continue;
            }
            String key = r.kind + " " + r.id + " " + (r.displayKey == null ? "" : r.displayKey);
            Integer at = slotByKey.get(key);
            if (at == null) {
                slotByKey.put(key, out.size());
                out.add(r);
            } else {
                InstanceReward cur = out.get(at);
                out.set(at, new InstanceReward(cur.kind, cur.id, cur.quantity + r.quantity, cur.displayKey,
                        cur.iconItemId));
            }
        }
        return out;
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
