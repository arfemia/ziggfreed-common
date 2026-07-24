package com.ziggfreed.common.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * The generic tool / any-item asset-tag entity-stats primitive (decision 26 RESOLVED full form):
 * an authored raw asset tag {@code Zig_Entity_Stats} (raw tags are {@code String[]}, per the
 * engine's tag storage shape) carrying {@code "<StatId>:<amount>"} entries, applied by {@link
 * EquipStatBridge} on the held-item triggers under its own {@code "<ns>:tag:<i>"} key space
 * (beside the native {@code *Weapon_N} convention and this package's per-stack {@link
 * StackStats}, so a tag-stat tool and an enhanced tool compose additively with zero
 * double-count risk).
 *
 * <p>Exists because the engine has no native path for TOOL asset stats: a {@code Weapon} block on
 * a tool zeroes hard-block mining power ({@code BlockHarvestUtils}), and a held item's own {@code
 * Utility.StatModifiers} is inert (utility stats apply only from the separate accessory slot). A
 * WEAPON or ARMOR item should author native {@code StatModifiers} directly instead of this tag.
 *
 * <p><b>Additive-only at launch.</b> Only the two-segment {@code "<StatId>:<amount>"} form
 * parses; a three-segment {@code "<StatId>:mult:<x>"} form is DOCS-RESERVED for a future
 * multiplicative channel and is deliberately NOT parsed here (rejected like any other malformed
 * entry, with a one-time warn per distinct raw string). A malformed amount (non-numeric) is
 * likewise rejected with a one-time warn. Duplicate stat ids within the SAME tag sum (mirrors
 * {@link StackStats#merge}'s summing convention).
 */
public final class HeldItemStatsTag {

    /** The raw asset tag holding {@code <StatId>:<amount>} entries. */
    public static final String TAG = "Zig_Entity_Stats";

    /** One-time-warn gate, keyed by the exact rejected raw entry string. */
    @Nonnull
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private HeldItemStatsTag() {
    }

    /**
     * The raw {@link #TAG} entries authored on {@code stack}'s item asset, or {@code null} when
     * the item has no asset / tag data / this tag at all.
     */
    @Nullable
    public static String[] rawEntries(@Nonnull ItemStack stack) {
        Item asset = stack.getItem();
        if (asset == null) {
            return null;
        }
        AssetExtraInfo.Data data = asset.getData();
        if (data == null) {
            return null;
        }
        return data.getRawTags().get(TAG);
    }

    /**
     * Parsed, summed {@link #TAG} entries for {@code stack} (empty map for a {@code null} stack,
     * no asset, no tag, or a tag with nothing parseable). Never throws.
     */
    @Nonnull
    public static Map<String, Double> entriesOf(@Nullable ItemStack stack) {
        if (stack == null) {
            return Map.of();
        }
        String[] raw;
        try {
            raw = rawEntries(stack);
        } catch (Throwable t) {
            warn("entriesOf", t);
            return Map.of();
        }
        if (raw == null || raw.length == 0) {
            return Map.of();
        }
        return parse(raw);
    }

    /** Pure parse core: {@code rawEntries} -> summed {@code statId -> amount} map. */
    @Nonnull
    static Map<String, Double> parse(@Nonnull String[] rawEntries) {
        Map<String, Double> parsed = new LinkedHashMap<>();
        for (String raw : rawEntries) {
            if (raw == null) {
                continue;
            }
            ParsedEntry entry = parseEntry(raw);
            if (entry == null) {
                continue;
            }
            parsed.merge(entry.statId, entry.amount, Double::sum);
        }
        return parsed;
    }

    /**
     * Parse ONE raw {@code "<StatId>:<amount>"} entry. Returns {@code null} (and warns once,
     * keyed by the exact raw string) for: an empty/blank entry, a three-segment {@code
     * "<StatId>:mult:<x>"} reserved-multiplicative entry, any other malformed segment count, an
     * empty stat id, or a non-numeric amount.
     */
    @Nullable
    static ParsedEntry parseEntry(@Nonnull String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            warnOnce(raw, "empty entry");
            return null;
        }
        String[] parts = trimmed.split(":");
        if (parts.length == 3) {
            if ("mult".equalsIgnoreCase(parts[1].trim())) {
                warnOnce(raw, "multiplicative form is docs-reserved, not parsed at launch");
            } else {
                warnOnce(raw, "malformed entry (unexpected segment count)");
            }
            return null;
        }
        if (parts.length != 2) {
            warnOnce(raw, "malformed entry (expected \"<StatId>:<amount>\")");
            return null;
        }
        String statId = parts[0].trim();
        if (statId.isEmpty()) {
            warnOnce(raw, "malformed entry (empty StatId)");
            return null;
        }
        try {
            double amount = Double.parseDouble(parts[1].trim());
            return new ParsedEntry(statId, amount);
        } catch (NumberFormatException e) {
            warnOnce(raw, "malformed amount");
            return null;
        }
    }

    private static void warnOnce(@Nonnull String raw, @Nonnull String reason) {
        if (WARNED.add(raw)) {
            try {
                ZiggfreedCommonPlugin.LOGGER.atWarning()
                        .log("HeldItemStatsTag: rejecting '" + TAG + "' entry '" + raw + "' - " + reason);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("HeldItemStatsTag." + label + " failed: " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }

    /** One parsed {@code "<StatId>:<amount>"} entry. Package-private test/inspection shape. */
    static final class ParsedEntry {
        @Nonnull
        final String statId;
        final double amount;

        ParsedEntry(@Nonnull String statId, double amount) {
            this.statId = statId;
            this.amount = amount;
        }
    }
}
