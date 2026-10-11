package com.ziggfreed.common.stats.damage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;

/**
 * Per-damage-type bonus damage an attacker deals, read from stats any gear can raise: for each damage
 * type the library ships a percent stat {@code DamageBonus_<Type>} (whole percent points, 10 is +10%) and
 * a flat stat {@code DamageBonusFlat_<Type>}, so an item's native {@code StatModifiers} on a server
 * running this library and nothing else make its wearer or wielder hit harder with that type. A mod adds
 * its own stats for a type with {@link #registerSource} (only what it adds, never a second copy of the
 * library's).
 *
 * <p><b>Applied per hit</b> by {@link TypedDamageBonusSystem}, in the Filter damage group: the hit's cause
 * and every cause it {@code Inherits} from are matched against the registered types, so a type key covers
 * its children (a {@code Fire} bonus reaches a hit whose cause is filed under Fire), and each matched
 * type's sources are summed once. The result is {@code (amount + flat) * (1 + percent / 100)}, floored at
 * zero: flat first, percent after, as the engine composes its own outgoing modifiers. A projectile hit is
 * its shooter's ({@code Damage.ProjectileSource} carries the shooter).
 *
 * <p>Pure apart from the stat and cause reads: {@link #apply} is the one arithmetic, shared by the damage
 * step and any tooltip that prints the number a hit will deal.
 */
public final class TypedDamageBonuses {

    private TypedDamageBonuses() {
    }

    /** The percent stat id prefix: {@code DamageBonus_<Type>}, whole percent points. */
    public static final String PERCENT_PREFIX = "DamageBonus_";

    /** The flat stat id prefix: {@code DamageBonusFlat_<Type>}, damage points. */
    public static final String FLAT_PREFIX = "DamageBonusFlat_";

    /**
     * The damage types the library ships a stat pair for: the game's own top-level and elemental types,
     * and the three this library adds beside them. Disjoint, so no hit matches two of them.
     */
    public static final List<String> TYPE_IDS = List.of("Physical", "Projectile", "Fire", "Ice", "Lightning",
            "Water", "Wind", "Earth", "Poison", "Arcane", "Void", "Life");

    /** Whether a registered source counts for this attacker right now (a world rule, say). */
    @FunctionalInterface
    public interface Gate {
        boolean allows(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker);
    }

    /** One registered stat pair for one type; either stat may be absent. */
    public record Source(@Nullable String percentStatId, @Nullable String flatStatId, @Nullable Gate gate) {
    }

    /** One attacker's summed bonus for a hit: whole percent points and flat damage. */
    public record Sums(double percentPoints, double flat) {

        public static final Sums NONE = new Sums(0.0, 0.0);

        public boolean isZero() {
            return percentPoints == 0.0 && flat == 0.0;
        }
    }

    /** Sources by lower-cased type id. */
    @Nonnull
    private static final Map<String, List<Source>> SOURCES = new ConcurrentHashMap<>();

    static {
        for (String type : TYPE_IDS) {
            registerSource(type, PERCENT_PREFIX + type, FLAT_PREFIX + type, null);
        }
    }

    /**
     * Count {@code percentStatId} (whole percent points) and {@code flatStatId} (damage points) toward
     * hits of {@code typeId} and every cause filed under it, while {@code gate} allows (always, when
     * null). Register once, at setup.
     */
    public static void registerSource(@Nonnull String typeId, @Nullable String percentStatId,
            @Nullable String flatStatId, @Nullable Gate gate) {
        if (typeId.isBlank() || (blank(percentStatId) && blank(flatStatId))) {
            return;
        }
        SOURCES.computeIfAbsent(typeId.toLowerCase(Locale.ROOT), k -> new CopyOnWriteArrayList<>())
                .add(new Source(blank(percentStatId) ? null : percentStatId, blank(flatStatId) ? null : flatStatId, gate));
    }

    /** The bonus step: {@code (amount + flat) * (1 + percentPoints / 100)}, floored at zero. */
    public static double apply(double amount, double percentPoints, double flat) {
        if ((percentPoints == 0.0 && flat == 0.0) || !Double.isFinite(percentPoints) || !Double.isFinite(flat)) {
            return amount;
        }
        return Math.max(0.0, (amount + flat) * (1.0 + percentPoints / 100.0));
    }

    /**
     * The registered type ids a hit of {@code causeId} matches: the cause and each ancestor it inherits
     * from that has sources, each once, nearest first. Reads the loaded {@code DamageCause} assets.
     */
    @Nonnull
    public static List<String> typesFor(@Nullable String causeId) {
        return typesFor(causeId, TypedDamageBonuses::loadedParentOf);
    }

    /** {@link #typesFor(String)} over a caller-supplied parent lookup (null for a root). */
    @Nonnull
    public static List<String> typesFor(@Nullable String causeId, @Nonnull Function<String, String> parentOf) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = causeId;
        while (current != null && !current.isBlank() && seen.add(current.toLowerCase(Locale.ROOT))) {
            if (SOURCES.containsKey(current.toLowerCase(Locale.ROOT))) {
                out.add(current);
            }
            current = parentOf.apply(current);
        }
        return out;
    }

    /**
     * The attacker's summed bonus for a hit of {@code causeId}: every source of every matched type whose
     * gate allows, read off the attacker's stats (each stat's current maximum, where native modifiers
     * accumulate). {@code excludeStatIds} leaves named stats out (a tooltip that prints those itself).
     */
    @Nonnull
    public static Sums sumsFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nullable String causeId, @Nonnull Collection<String> excludeStatIds) {
        List<String> types = typesFor(causeId);
        if (types.isEmpty()) {
            return Sums.NONE;
        }
        EntityStatMap stats = accessor.getComponent(attacker, EntityStatMap.getComponentType());
        if (stats == null) {
            return Sums.NONE;
        }
        double percent = 0.0;
        double flat = 0.0;
        for (String type : types) {
            List<Source> sources = SOURCES.get(type.toLowerCase(Locale.ROOT));
            if (sources == null) {
                continue;
            }
            for (Source source : sources) {
                if (source.gate() != null && !source.gate().allows(accessor, attacker)) {
                    continue;
                }
                if (source.percentStatId() != null && !excludeStatIds.contains(source.percentStatId())) {
                    percent += readMax(stats, source.percentStatId());
                }
                if (source.flatStatId() != null && !excludeStatIds.contains(source.flatStatId())) {
                    flat += readMax(stats, source.flatStatId());
                }
            }
        }
        return new Sums(percent, flat);
    }

    private static double readMax(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        int idx = StatIndexCache.resolve(statId);
        if (idx < 0) {
            return 0.0;
        }
        EntityStatValue value = stats.get(idx);
        double max = value == null ? 0.0 : value.getMax();
        // No cap stands behind this read, so a non-finite maximum must never reach a hit's amount.
        return Double.isFinite(max) ? max : 0.0;
    }

    @Nullable
    private static String loadedParentOf(@Nonnull String causeId) {
        try {
            DamageCause cause = DamageCause.getAssetMap().getAsset(causeId);
            return cause != null ? cause.getInherits() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
