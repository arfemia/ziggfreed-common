package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One weighted, score-gated, quantity-ranged entry in a {@link LootTable}'s rollable pool: an
 * {@link InstanceReward} TEMPLATE (kind + id + an inclusive quantity range) plus the policy a roll
 * needs - a pick {@link #weight} and a {@link #minScore} eligibility gate. A roll filters the pool to
 * the entries a player's score unlocks ({@code minScore <= score}), weighted-picks among them, and
 * {@link #resolve}s each pick to a concrete {@link InstanceReward} with a rolled quantity. This is the
 * "better loot for a better score" gate; the bare {@link InstanceReward} (a fixed grant) stays for the
 * table's guaranteed list.
 *
 * <p>Pack-authored as a compact spec string (the codec has no list-of-objects form, so the pool is a
 * {@code String[]} just like {@code InstancePresetAsset.Rewards}). The grammar is a superset of
 * {@link InstanceReward}'s - optional leading {@code w}/{@code s}/gate flag tokens (any order), then the
 * same {@code <kind> <id> <qty> [displayKey]} tail, where {@code qty} may be a single count or an
 * inclusive {@code min-max} range:
 * <pre>
 *   [w&lt;weight&gt;] [s&lt;minScore&gt;] [win|loss|any] item     &lt;itemId&gt;     &lt;qty|qtyMin-qtyMax&gt; [displayKey]
 *   [w&lt;weight&gt;] [s&lt;minScore&gt;] [win|loss|any] currency &lt;currencyId&gt; &lt;qty|qtyMin-qtyMax&gt; [displayKey]
 * </pre>
 * A consumer-registered token (see {@link RewardSpecRegistry}, e.g. {@code xp}) is accepted in the
 * {@code <kind>} slot too. Examples: {@code "w12 item KweebecNightmare_Gustbloom 1-2"},
 * {@code "w4 s4000 item Ingredient_Life_Essence_Concentrated 1"},
 * {@code "any xp MINING 500 mymod.reward.xp.mining"}. Absent {@code w}/{@code s} default to weight 1 /
 * score 0 (always eligible); an absent gate defaults to {@link WinGate#WIN} (win-only, the historical
 * whole-table ON_WIN behaviour). Ids never contain spaces (a registered token's rewritten id may).
 */
public record LootEntry(@Nonnull InstanceReward.Kind kind, @Nonnull String id, int qtyMin, int qtyMax,
                        int weight, int minScore, @Nullable String displayKey, @Nullable String iconItemId,
                        @Nonnull WinGate gate) {

    public LootEntry {
        qtyMin = Math.max(1, qtyMin);
        qtyMax = Math.max(qtyMin, qtyMax);
        if (gate == null) {
            gate = WinGate.WIN;
        }
    }

    /** Pick weight clamped to a non-negative value (a negative authored weight reads as 0). */
    public double safeWeight() {
        return Math.max(0, weight);
    }

    /**
     * Resolve this template to a concrete {@link InstanceReward}, rolling the quantity uniformly in
     * {@code [qtyMin, qtyMax]} via {@code rng} (a fixed single count when the range is degenerate).
     */
    @Nonnull
    public InstanceReward resolve(@Nonnull Random rng) {
        int qty = qtyMax > qtyMin ? qtyMin + rng.nextInt(qtyMax - qtyMin + 1) : qtyMin;
        return new InstanceReward(kind, id, qty, displayKey, iconItemId);
    }

    /**
     * Parse one compact pool spec (see the class doc). Returns {@code null} for a blank, malformed, or
     * unknown-kind spec (the caller skips it).
     */
    @Nullable
    public static LootEntry parse(@Nullable String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] p = spec.trim().split("\\s+");
        int i = 0;
        int weight = 1;
        int minScore = 0;
        WinGate gate = WinGate.WIN;
        // Consume leading w<n> / s<n> / win|loss|any flag tokens (any order) until the kind token.
        // flagValue requires digits after the prefix, so a win/loss token never collides with w<n>.
        while (i < p.length) {
            Integer w = flagValue(p[i], 'w');
            Integer s = flagValue(p[i], 's');
            WinGate g = gateToken(p[i]);
            if (w != null) {
                weight = w;
                i++;
            } else if (s != null) {
                minScore = s;
                i++;
            } else if (g != null) {
                gate = g;
                i++;
            } else {
                break;
            }
        }
        // Remaining must be: <kind> <id> <qty> [displayKey].
        if (p.length - i < 3) {
            return null;
        }
        String token = p[i].toLowerCase(Locale.ROOT);
        InstanceReward.Kind kind;
        String id;
        String iconItemId = null;
        switch (token) {
            case "item":
                kind = InstanceReward.Kind.ITEM;
                id = p[i + 1];
                break;
            case "currency":
                kind = InstanceReward.Kind.CURRENCY;
                id = p[i + 1];
                break;
            default:
                RewardSpecRegistry.Binding b = RewardSpecRegistry.lookup(token);
                if (b == null) {
                    return null;
                }
                kind = b.kind();
                id = b.idTransform().apply(p[i + 1]);
                iconItemId = b.iconResolver().apply(p[i + 1]);
        }
        int[] qty = parseQty(p[i + 2]);
        if (qty == null) {
            return null;
        }
        String displayKey = p.length - i >= 4 ? p[i + 3] : null;
        return new LootEntry(kind, id, qty[0], qty[1], weight, minScore, displayKey, iconItemId, gate);
    }

    /** The {@link WinGate} for a bare {@code win}/{@code loss}/{@code any} token, or {@code null} if not one. */
    @Nullable
    private static WinGate gateToken(@Nonnull String tok) {
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "win" -> WinGate.WIN;
            case "loss" -> WinGate.LOSS;
            case "any" -> WinGate.ANY;
            default -> null;
        };
    }

    /** Parse a spec array into a pool list, skipping any malformed entries. */
    @Nonnull
    public static List<LootEntry> parseAll(@Nullable String[] specs) {
        List<LootEntry> out = new ArrayList<>();
        if (specs == null) {
            return out;
        }
        for (String spec : specs) {
            LootEntry e = parse(spec);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** The int after a {@code <prefix><digits>} flag token (e.g. {@code w12} -> 12), or {@code null} if it is not that flag. */
    @Nullable
    private static Integer flagValue(@Nonnull String tok, char prefix) {
        if (tok.length() < 2 || tok.charAt(0) != prefix) {
            return null;
        }
        for (int k = 1; k < tok.length(); k++) {
            if (!Character.isDigit(tok.charAt(k))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(tok.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse a {@code N} or {@code N-M} quantity token to {@code [min, max]}; {@code null} if malformed (min &lt; 1 or max &lt; min). */
    @Nullable
    private static int[] parseQty(@Nonnull String tok) {
        int dash = tok.indexOf('-');
        try {
            if (dash > 0) {
                int min = Integer.parseInt(tok.substring(0, dash));
                int max = Integer.parseInt(tok.substring(dash + 1));
                if (min < 1 || max < min) {
                    return null;
                }
                return new int[]{min, max};
            }
            int q = Integer.parseInt(tok);
            if (q < 1) {
                return null;
            }
            return new int[]{q, q};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
