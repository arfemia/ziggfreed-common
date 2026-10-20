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
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.HitTags;
import com.ziggfreed.common.stats.StatIndexCache;

/**
 * Per-damage-type bonus damage an attacker deals, read from stats any gear can raise: for each damage
 * type the library ships a percent stat {@code DamageBonus_<Type>} (whole percent points, 10 is +10%) and
 * a flat stat {@code DamageBonusFlat_<Type>}, so an item's native {@code StatModifiers} on a server
 * running this library and nothing else make its wearer or wielder hit harder with that type. A mod adds
 * its own stats for a type with {@link #registerSource} (only what it adds, never a second copy of the
 * library's), or for every type at once under {@link #EVERY_TYPE}.
 *
 * <p><b>Summed as the engine sums Enrage</b> (R567), {@code (amount + flat) * (1 + every percent summed)}:
 * zc-effects' {@code DamageBonusReconciler} keeps each player's summed PERCENT sources on hidden native
 * {@code OutgoingDamage} effects the engine applies, and {@link TypedDamageBonusSystem} adds the FLAT
 * sources ({@link #flatFor}) into that same sum on a player's direct hit, never a damage-over-time tick.
 * A type key covers its children (the engine's outgoing walk follows a hit's {@code Inherits} chain), so a
 * {@code Fire} bonus reaches a hit filed under Fire. Each type's summed percent is held to the attacker's
 * {@value #CEILING_STAT_ID} stat (its asset's {@code Max} is the ceiling every player starts with) and to
 * {@link #FLOOR_PERCENT} below.
 *
 * <p><b>Route sources</b> ({@link #registerRouteSource}) count only on the hits a {@link Route} names (a
 * hit's {@code Tags.Hit} value, a projectile): a line that belongs to how a hit was dealt rather than to
 * what the attacker holds. {@link TypedDamageBonusSystem} adds them per hit into the same sum the engine
 * built ({@link NativeOutgoing#addToSum}), so they add to Enrage rather than multiply on top of it.
 *
 * <p>{@link #apply} is the one arithmetic, shared by any tooltip that prints the number a hit will deal.
 */
public final class TypedDamageBonuses {

    private TypedDamageBonuses() {
    }

    /** The percent stat id prefix: {@code DamageBonus_<Type>}, whole percent points. */
    public static final String PERCENT_PREFIX = "DamageBonus_";

    /** The flat stat id prefix: {@code DamageBonusFlat_<Type>}, damage points. */
    public static final String FLAT_PREFIX = "DamageBonusFlat_";

    /**
     * The type id a source registers under to count toward every library type ({@link #TYPE_IDS}) at
     * once: a line that raises all of an attacker's damage, whatever its cause.
     */
    public static final String EVERY_TYPE = "*";

    /** The ceiling stat: the most one type's summed percent bonus reaches, whole percent points. */
    public static final String CEILING_STAT_ID = "DamageBonusMax";

    /**
     * The FALLBACK ceiling, whole percent points, standing in only when the {@value #CEILING_STAT_ID} asset
     * is not loaded (early boot, a unit JVM, a failed load). The shipped asset's {@code Max} is the
     * authority: this constant mirrors it and moves when the asset moves ({@code DamageBonusStatsTest}
     * holds the two equal).
     */
    public static final int FALLBACK_CEILING_PERCENT = 1000;

    /** A whole hit off: the lowest a summed percent bonus goes (the engine floors the damage at zero). */
    public static final int FLOOR_PERCENT = -100;

    /**
     * The damage types the library ships a stat pair for: the game's own top-level and elemental types,
     * and the three this library adds beside them. Disjoint, so no hit matches two of them. The
     * resistance stats ({@link DamageResistances}) cover the same types and the resist-only Fall.
     */
    public static final List<String> TYPE_IDS = List.of("Physical", "Projectile", "Fire", "Ice", "Lightning",
            "Water", "Wind", "Earth", "Poison", "Arcane", "Void", "Life");

    /** Whether a registered source counts for this attacker right now (a world rule, a held weapon). */
    @FunctionalInterface
    public interface Gate {
        boolean allows(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker);
    }

    /**
     * Which hits a route source counts on: decided from the hit itself (its tags, its source), never from
     * what the attacker holds when it lands. Asked only when the source's stats read nonzero.
     */
    @FunctionalInterface
    public interface Route {

        boolean matches(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
                @Nonnull Damage damage);

        /** A hit whose dealing node carries {@code value} under {@code Tags.Hit} (ignoring case). */
        @Nonnull
        static Route hitTag(@Nonnull String value) {
            return (accessor, attacker, damage) -> HitTags.has(damage, value);
        }

        /** A hit a projectile dealt (an arrow, a thrown spear, a bomb's burst). */
        @Nonnull
        static Route projectile() {
            return (accessor, attacker, damage) -> damage.getSource() instanceof Damage.ProjectileSource;
        }
    }

    /** One registered stat pair for one type; either stat may be absent. */
    public record Source(@Nullable String percentStatId, @Nullable String flatStatId, @Nullable Gate gate) {
    }

    /** One registered route stat pair for one type; either stat may be absent. */
    public record RouteSource(@Nullable String percentStatId, @Nullable String flatStatId, @Nonnull Route route) {
    }

    /** One attacker's summed bonus: whole percent points and flat damage. */
    public record Sums(double percentPoints, double flat) {

        public static final Sums NONE = new Sums(0.0, 0.0);

        public boolean isZero() {
            return percentPoints == 0.0 && flat == 0.0;
        }
    }

    /** Sources by lower-cased type id ({@link #EVERY_TYPE} for every type). */
    @Nonnull
    private static final Map<String, List<Source>> SOURCES = new ConcurrentHashMap<>();

    /** Route sources by lower-cased type id ({@link #EVERY_TYPE} for every type). */
    @Nonnull
    private static final Map<String, List<RouteSource>> ROUTES = new ConcurrentHashMap<>();

    /** Each registered type id in its first registered spelling, {@link #EVERY_TYPE} left out. */
    @Nonnull
    private static final Map<String, String> TYPE_SPELLING = new ConcurrentHashMap<>();

    /** {@link #TYPE_SPELLING}'s values as one immutable list, rebuilt only when a type is added (a per-tick read). */
    @Nonnull
    private static volatile List<String> registeredTypes = List.of();

    /** Asked before any source of any type; null (the default) lets every attacker's bonuses count. */
    @Nullable
    private static volatile Gate attackerGate;

    static {
        for (String type : TYPE_IDS) {
            registerSource(type, PERCENT_PREFIX + type, FLAT_PREFIX + type, null);
        }
    }

    /**
     * Count {@code percentStatId} (whole percent points) and {@code flatStatId} (damage points) toward
     * hits of {@code typeId} and every cause filed under it ({@link #EVERY_TYPE}: every library type),
     * while {@code gate} allows (always, when null). Registering the same stat pair for the same type
     * again replaces the earlier registration, so a late re-entry (a skill a pack folds in) is safe.
     */
    public static void registerSource(@Nonnull String typeId, @Nullable String percentStatId,
            @Nullable String flatStatId, @Nullable Gate gate) {
        if (typeId.isBlank() || (blank(percentStatId) && blank(flatStatId))) {
            return;
        }
        Source source = new Source(blank(percentStatId) ? null : percentStatId, blank(flatStatId) ? null : flatStatId,
                gate);
        List<Source> list = SOURCES.computeIfAbsent(key(typeId), k -> new CopyOnWriteArrayList<>());
        list.removeIf(s -> sameStats(s.percentStatId(), s.flatStatId(), source.percentStatId(), source.flatStatId()));
        list.add(source);
        noteSpelling(typeId);
    }

    /**
     * Count {@code percentStatId} and {@code flatStatId} toward hits of {@code typeId} ({@link #EVERY_TYPE}:
     * every library type) that {@code route} names, on top of the engine's sum for that hit
     * ({@link TypedDamageBonusSystem}). The same stat pair for the same type again replaces the earlier one.
     */
    public static void registerRouteSource(@Nonnull String typeId, @Nullable String percentStatId,
            @Nullable String flatStatId, @Nonnull Route route) {
        if (typeId.isBlank() || (blank(percentStatId) && blank(flatStatId))) {
            return;
        }
        RouteSource source = new RouteSource(blank(percentStatId) ? null : percentStatId,
                blank(flatStatId) ? null : flatStatId, route);
        List<RouteSource> list = ROUTES.computeIfAbsent(key(typeId), k -> new CopyOnWriteArrayList<>());
        list.removeIf(s -> sameStats(s.percentStatId(), s.flatStatId(), source.percentStatId(), source.flatStatId()));
        list.add(source);
        noteSpelling(typeId);
    }

    /**
     * Install the one rule asked before any bonus counts for an attacker (a world that plays plain
     * combat, say): while it answers false, the attacker's hits take no per-type bonus at all, the
     * library's own stats included. Null removes it; with none installed every bonus counts. Set once, at
     * setup.
     */
    public static void installAttackerGate(@Nullable Gate gate) {
        attackerGate = gate;
    }

    /** Whether bonuses count for {@code attacker} now: the installed rule's answer, else true. */
    public static boolean attackerAllowed(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker) {
        Gate gate = attackerGate;
        return gate == null || gate.allows(accessor, attacker);
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
     * from that has sources or route sources, each once, nearest first ({@link #EVERY_TYPE} is never in
     * it). Reads the loaded {@code DamageCause} assets.
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
        while (current != null && !current.isBlank() && seen.add(key(current))) {
            if (SOURCES.containsKey(key(current)) || ROUTES.containsKey(key(current))) {
                out.add(current);
            }
            current = parentOf.apply(current);
        }
        return out;
    }

    /** Every type id a source or route source is registered under, in its registered spelling. */
    @Nonnull
    public static List<String> registeredTypes() {
        return registeredTypes;
    }

    /**
     * The attacker's summed bonus for a hit of {@code causeId}: the {@link #EVERY_TYPE} sources and every
     * source of every matched type whose gate allows, read off the attacker's stats (each stat's current
     * maximum, where native modifiers accumulate). Route sources are not in it. {@code excludeStatIds}
     * leaves named stats out (a tooltip that prints those itself).
     */
    @Nonnull
    public static Sums sumsFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nullable String causeId, @Nonnull Collection<String> excludeStatIds) {
        if (!attackerAllowed(accessor, attacker)) {
            return Sums.NONE;
        }
        EntityStatMap stats = accessor.getComponent(attacker, EntityStatMap.getComponentType());
        if (stats == null) {
            return Sums.NONE;
        }
        double[] sum = new double[2];
        addSources(accessor, attacker, stats, SOURCES.get(EVERY_TYPE), excludeStatIds, sum);
        for (String type : typesFor(causeId)) {
            addSources(accessor, attacker, stats, SOURCES.get(key(type)), excludeStatIds, sum);
        }
        return new Sums(sum[0], sum[1]);
    }

    /**
     * The attacker's summed FLAT bonus for a hit of {@code causeId}: the flat stat of every
     * {@link #EVERY_TYPE} source and of every matched type's sources whose gate allows. Only the flat
     * stats are read, and a gate is asked only of a nonzero one. The route step adds it to a direct hit.
     */
    public static double flatFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nullable String causeId) {
        if (!attackerAllowed(accessor, attacker)) {
            return 0.0;
        }
        EntityStatMap stats = accessor.getComponent(attacker, EntityStatMap.getComponentType());
        if (stats == null) {
            return 0.0;
        }
        double flat = addFlats(accessor, attacker, stats, SOURCES.get(EVERY_TYPE));
        for (String type : typesFor(causeId)) {
            flat += addFlats(accessor, attacker, stats, SOURCES.get(key(type)));
        }
        return flat;
    }

    /**
     * The sources registered under exactly {@code typeId} ({@link #EVERY_TYPE} for the every-type ones),
     * no ancestor walk and no attacker rule: the per-type reading the native reconciler keys one effect on.
     */
    @Nonnull
    public static Sums sumsOfType(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull String typeId, @Nonnull EntityStatMap stats) {
        double[] sum = new double[2];
        addSources(accessor, attacker, stats, SOURCES.get(key(typeId)), List.of(), sum);
        return sum[0] == 0.0 && sum[1] == 0.0 ? Sums.NONE : new Sums(sum[0], sum[1]);
    }

    /**
     * The route sources a hit of {@code causeId} takes: the {@link #EVERY_TYPE} ones and those of every
     * matched type whose route names this hit, read off the attacker's stats. A source whose stats read
     * zero is never asked its route.
     */
    @Nonnull
    public static Sums routeSumsFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nullable String causeId) {
        if (ROUTES.isEmpty() || !attackerAllowed(accessor, attacker)) {
            return Sums.NONE;
        }
        EntityStatMap stats = accessor.getComponent(attacker, EntityStatMap.getComponentType());
        if (stats == null) {
            return Sums.NONE;
        }
        double[] sum = new double[2];
        addRoutes(accessor, attacker, damage, stats, ROUTES.get(EVERY_TYPE), sum);
        for (String type : typesFor(causeId)) {
            addRoutes(accessor, attacker, damage, stats, ROUTES.get(key(type)), sum);
        }
        return sum[0] == 0.0 && sum[1] == 0.0 ? Sums.NONE : new Sums(sum[0], sum[1]);
    }

    /**
     * {@code routePoints} held so that the native lines this library already put on ({@code nativePoints})
     * plus it stay inside {@code [FLOOR_PERCENT, ceiling]}: the route's share of the ceiling's headroom.
     */
    public static double heldToCeiling(double nativePoints, double routePoints, double ceiling) {
        return held(nativePoints + routePoints, ceiling) - held(nativePoints, ceiling);
    }

    /**
     * The ceiling for {@code attacker}, whole percent points, never below zero: its own
     * {@value #CEILING_STAT_ID}, else the shipped asset's {@code Max} (else its mirror) when the stat is
     * not on the entity yet.
     */
    public static double ceilingPercent(@Nonnull EntityStatMap stats) {
        int idx = StatIndexCache.resolve(CEILING_STAT_ID);
        double ceiling = idx >= 0 && stats.get(idx) != null ? readMax(stats, CEILING_STAT_ID) : assetCeilingPercent();
        return Math.max(0.0, ceiling);
    }

    /**
     * The ceiling with no entity in hand: the {@value #CEILING_STAT_ID} asset's own {@code Max}, or
     * {@link #FALLBACK_CEILING_PERCENT} (its mirror) when no such asset is loaded.
     */
    public static double assetCeilingPercent() {
        try {
            int idx = StatIndexCache.resolve(CEILING_STAT_ID);
            EntityStatType asset = idx < 0 ? null : EntityStatType.getAssetMap().getAsset(idx);
            return asset == null ? FALLBACK_CEILING_PERCENT : Math.max(0.0, asset.getMax());
        } catch (Throwable t) {
            return FALLBACK_CEILING_PERCENT;
        }
    }

    /** A stat's current maximum, where native modifiers accumulate; 0 when unregistered or not finite. */
    static double readMax(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        int idx = StatIndexCache.resolve(statId);
        if (idx < 0) {
            return 0.0;
        }
        EntityStatValue value = stats.get(idx);
        double max = value == null ? 0.0 : value.getMax();
        // No cap stands behind this read, so a non-finite maximum must never reach a hit's amount.
        return Double.isFinite(max) ? max : 0.0;
    }

    /** Adds each source's reading to {@code sum} ({percent, flat}); a gate is asked only of a nonzero one. */
    private static void addSources(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull EntityStatMap stats, @Nullable List<Source> sources, @Nonnull Collection<String> excludeStatIds,
            @Nonnull double[] sum) {
        if (sources == null) {
            return;
        }
        for (Source source : sources) {
            double percent = source.percentStatId() != null && !excludeStatIds.contains(source.percentStatId())
                    ? readMax(stats, source.percentStatId()) : 0.0;
            double flat = source.flatStatId() != null && !excludeStatIds.contains(source.flatStatId())
                    ? readMax(stats, source.flatStatId()) : 0.0;
            if (percent == 0.0 && flat == 0.0) {
                continue;
            }
            if (source.gate() != null && !source.gate().allows(accessor, attacker)) {
                continue;
            }
            sum[0] += percent;
            sum[1] += flat;
        }
    }

    /** The flat stats of {@code sources} summed; a gate is asked only of a nonzero one. */
    private static double addFlats(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull EntityStatMap stats, @Nullable List<Source> sources) {
        if (sources == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (Source source : sources) {
            double flat = source.flatStatId() != null ? readMax(stats, source.flatStatId()) : 0.0;
            if (flat == 0.0 || (source.gate() != null && !source.gate().allows(accessor, attacker))) {
                continue;
            }
            sum += flat;
        }
        return sum;
    }

    /** Adds each route source's reading whose route names {@code damage}; a route is asked only of a nonzero one. */
    private static void addRoutes(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull EntityStatMap stats, @Nullable List<RouteSource> sources,
            @Nonnull double[] sum) {
        if (sources == null) {
            return;
        }
        for (RouteSource source : sources) {
            double percent = source.percentStatId() != null ? readMax(stats, source.percentStatId()) : 0.0;
            double flat = source.flatStatId() != null ? readMax(stats, source.flatStatId()) : 0.0;
            if (percent == 0.0 && flat == 0.0) {
                continue;
            }
            if (!source.route().matches(accessor, attacker, damage)) {
                continue;
            }
            sum[0] += percent;
            sum[1] += flat;
        }
    }

    private static double held(double points, double ceiling) {
        if (!Double.isFinite(points)) {
            return 0.0;
        }
        return Math.max(FLOOR_PERCENT, Math.min(Math.max(0.0, ceiling), points));
    }

    private static void noteSpelling(@Nonnull String typeId) {
        if (!EVERY_TYPE.equals(typeId) && TYPE_SPELLING.putIfAbsent(key(typeId), typeId) == null) {
            synchronized (TYPE_SPELLING) {
                registeredTypes = List.copyOf(TYPE_SPELLING.values());
            }
        }
    }

    private static boolean sameStats(@Nullable String percentA, @Nullable String flatA, @Nullable String percentB,
            @Nullable String flatB) {
        return same(percentA, percentB) && same(flatA, flatB);
    }

    private static boolean same(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    @Nonnull
    private static String key(@Nonnull String typeId) {
        return typeId.toLowerCase(Locale.ROOT);
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
