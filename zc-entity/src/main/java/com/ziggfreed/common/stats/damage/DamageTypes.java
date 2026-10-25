package com.ziggfreed.common.stats.damage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

/**
 * The damage types the library's stat families cover, read off the loaded assets rather than a list in code (R858):
 * a type is a loaded {@code DamageCause} that a loaded stat file names under the family's prefix. A damage type is a
 * cause with a {@code DamageBonus_<Cause>} stat ({@link #bonusTypes}); a resistance type a cause with a
 * {@code DamageResist_<Cause>} stat ({@link #resistTypes}), so the game's {@code Fall}, which deals no damage of its
 * own and ships no bonus, is a resistance type and no damage type. A pack that adds a cause or an Element and ships its
 * {@code DamageBonus_<Cause>} or {@code DamageResist_<Cause>} file gets the bonus or the resistance with no Java.
 *
 * <p>Each roster is built once from the two asset maps and kept until either reloads ({@link #invalidate}, which the
 * library's bootstrap calls on every stat or cause load); each type is in its cause's loaded spelling, in the cause
 * map's order. Empty until both stores are loaded (a unit JVM never loads them).
 */
public final class DamageTypes {

    private DamageTypes() {
    }

    /** One roster as it was last built: its types and each by lower-cased id. */
    private record Roster(@Nonnull List<String> types, @Nonnull Map<String, String> byKey) {
        static final Roster EMPTY = new Roster(List.of(), Map.of());
    }

    @Nullable
    private static volatile Roster bonus;

    @Nullable
    private static volatile Roster resist;

    /** The damage types: every loaded cause with a loaded {@code DamageBonus_<Cause>} stat. */
    @Nonnull
    public static List<String> bonusTypes() {
        return bonusRoster().types();
    }

    /** The resistance types: every loaded cause with a loaded {@code DamageResist_<Cause>} stat. */
    @Nonnull
    public static List<String> resistTypes() {
        return resistRoster().types();
    }

    /** {@code typeId} in its damage-type spelling (matched ignoring case), or null when it is no damage type. */
    @Nullable
    public static String bonusType(@Nullable String typeId) {
        return typeId == null ? null : bonusRoster().byKey().get(typeId.toLowerCase(Locale.ROOT));
    }

    /** {@code typeId} in its resistance-type spelling (matched ignoring case), or null when it is no resistance type. */
    @Nullable
    public static String resistType(@Nullable String typeId) {
        return typeId == null ? null : resistRoster().byKey().get(typeId.toLowerCase(Locale.ROOT));
    }

    /** Forget both rosters: a stat or cause load moved what they read. */
    public static void invalidate() {
        bonus = null;
        resist = null;
    }

    /**
     * The types {@code prefix} names among {@code statIds}: each of {@code causeIds} (in its order and spelling) for
     * which a stat {@code prefix + cause} is there, matched ignoring case. Pure.
     */
    @Nonnull
    public static List<String> typesOf(@Nonnull Collection<String> statIds, @Nonnull Collection<String> causeIds,
            @Nonnull String prefix) {
        Set<String> named = new HashSet<>();
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        for (String id : statIds) {
            if (id == null) {
                continue;
            }
            String lower = id.toLowerCase(Locale.ROOT);
            if (lower.startsWith(lowerPrefix) && lower.length() > lowerPrefix.length()) {
                named.add(lower.substring(lowerPrefix.length()));
            }
        }
        List<String> out = new ArrayList<>();
        for (String cause : causeIds) {
            if (cause != null && named.contains(cause.toLowerCase(Locale.ROOT)) && !out.contains(cause)) {
                out.add(cause);
            }
        }
        return out;
    }

    @Nonnull
    private static Roster bonusRoster() {
        Roster held = bonus;
        if (held == null) {
            held = build(TypedDamageBonuses.PERCENT_PREFIX);
            bonus = held;
        }
        return held;
    }

    @Nonnull
    private static Roster resistRoster() {
        Roster held = resist;
        if (held == null) {
            held = build(DamageResistances.PREFIX);
            resist = held;
        }
        return held;
    }

    @Nonnull
    private static Roster build(@Nonnull String prefix) {
        try {
            var stats = EntityStatType.getAssetMap();
            var causes = DamageCause.getAssetMap();
            if (stats == null || causes == null) {
                return Roster.EMPTY;
            }
            List<String> types = typesOf(stats.getAssetMap().keySet(), causes.getAssetMap().keySet(), prefix);
            Map<String, String> byKey = new LinkedHashMap<>();
            for (String type : types) {
                byKey.putIfAbsent(type.toLowerCase(Locale.ROOT), type);
            }
            return new Roster(List.copyOf(types), Map.copyOf(byKey));
        } catch (Throwable t) {
            return Roster.EMPTY;
        }
    }
}
