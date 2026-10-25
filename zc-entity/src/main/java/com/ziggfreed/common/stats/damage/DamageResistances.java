package com.ziggfreed.common.stats.damage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;

/**
 * Per-damage-type resistance, read from stats any gear can raise: a resistance type is any loaded cause with
 * a loaded {@code DamageResist_<Type>} stat ({@link #types}, read off the loaded files, never a list here,
 * R858: the damage types and the game's {@value #FALL}), whole percent points, 10 resists a tenth of each hit
 * of the type, and {@value #EVERY_TYPE_STAT_ID} resists every damage type at once (every resistance type with
 * a damage bonus of its own, so never {@value #FALL}). An item's native {@code StatModifiers} on a server
 * running this library and nothing else make its wearer or wielder take less, a creature as much as a player
 * (R860). Two items carrying one add up, as any stat's modifiers do, and a consumer counts a stat of its own
 * toward a type, or every damage type under {@link #EVERY_TYPE}, with {@link #registerSource}.
 *
 * <p><b>The engine applies it, on the type's own cause</b> (R674): the engine reads resistance only from
 * worn armor and active effects, never from a stat, so zc-effects' {@code DamageResistReconciler} keeps one
 * hidden effect per type on each carrier whose native {@code DamageResistance} is that type's reading, keyed
 * on exactly that cause as vanilla armor keys {@code Physical}: a Crush, Bludgeoning or Slashing hit is NOT
 * reduced by a {@code Physical} resistance, as armor's is not. The engine sums it with worn armor and every other
 * resistance effect on the key. Nothing reads these stats at a damage site: a fold that did would mitigate
 * the same points twice. This class is the stat family: its ids, its sources, its ceiling and the two
 * consumer rules over it. {@link NativeResistance} reads the engine's own sum.
 *
 * <p><b>The ceiling</b> is the {@value #CEILING_STAT_ID} stat, read off the entity like any stat: its asset's
 * own {@code Max} is the ceiling every player starts with (content a server retunes by a same-id file), and
 * gear can raise or lower it. It bounds a type's SUMMED resistance, worn armor and every other effect on the
 * key included: this library's share takes only the headroom the rest leaves, so the sum stops short of
 * immunity (armor alone past the ceiling is the game's own and stays). One consumer rule may answer the
 * ceiling instead ({@link #installCeiling}); no ceiling passes {@link #HIGHEST_PERCENT}, one short of a whole hit.
 *
 * <p><b>The world rule:</b> one consumer rule may switch every resistance off for an entity
 * ({@link #installGate}, a world that plays plain combat, say); with none installed every one counts.
 */
public final class DamageResistances {

    private DamageResistances() {
    }

    /** The resistance stat id prefix: {@code DamageResist_<Type>}, whole percent points. */
    public static final String PREFIX = "DamageResist_";

    /**
     * The every-type resistance stat, whole percent points: counted toward every damage type's resistance
     * ({@link DamageTypes#bonusTypes}), so never toward {@value #FALL}.
     */
    public static final String EVERY_TYPE_STAT_ID = "DamageResist";

    /** The type id a source registers under to count toward every damage type's resistance at once. */
    public static final String EVERY_TYPE = TypedDamageBonuses.EVERY_TYPE;

    /** The ceiling stat: the most of one type's hits the summed resistance covers, whole percent points. */
    public static final String CEILING_STAT_ID = "DamageResistMax";

    /**
     * The game's own {@code Fall} cause: it ships a resistance stat and no damage bonus (nothing deals fall
     * damage), so it is a resistance type ({@link #types}) and never a damage type, and the every-type
     * resistance never reaches it.
     */
    public static final String FALL = "Fall";

    /**
     * The rolled-landing stat, whole percent points: a {@value #FALL} hit taken while the player rolls the
     * landing takes this much off on top of the {@code DamageResist_Fall} sum, held under the ceiling with
     * the rest. The game's own roll reads the world's movement config, the same for everyone; this is the
     * per-player part. zc-effects' after-armor step reads it on the hit.
     */
    public static final String ROLL_STAT_ID = "RollLanding";

    /** A whole hit: the semantic bound past which a resistance means nothing more. */
    public static final int FULL_PERCENT = 100;

    /**
     * The most any ceiling answers, whole percent points: one short of a whole hit, so no raised
     * {@value #CEILING_STAT_ID} or consumer rule lets a summed resistance reach immunity (R240).
     */
    public static final int HIGHEST_PERCENT = FULL_PERCENT - 1;

    /**
     * The FALLBACK ceiling, whole percent points, standing in only when the {@value #CEILING_STAT_ID} asset
     * is not loaded (early boot, a unit JVM, a failed load). The shipped asset's {@code Max} is the
     * authority: this constant mirrors it, so a missing asset fails closed toward the shipped bound rather
     * than open to a whole hit, and it moves when the asset moves ({@code DamageResistStatsTest} holds the
     * two equal).
     */
    public static final int FALLBACK_CEILING_PERCENT = 80;

    /**
     * The resistance types: every loaded cause with a loaded {@code DamageResist_<Type>} stat, in its loaded
     * spelling ({@link DamageTypes#resistTypes}). Empty before the stores load.
     */
    @Nonnull
    public static List<String> types() {
        return DamageTypes.resistTypes();
    }

    /**
     * One consumer stat counted toward a type's resistance, whole percent points: read off the holder like
     * the type's own stat and added to it before the ceiling, through {@code hold} when one is given (a
     * consumer's own rail on its share, say).
     */
    public record Source(@Nonnull String statId, @Nullable DoubleUnaryOperator hold) {
    }

    /** Sources by lower-cased type id. */
    @Nonnull
    private static final Map<String, List<Source>> SOURCES = new ConcurrentHashMap<>();

    /** Whether resistance counts for this entity right now (a world rule, say). */
    @FunctionalInterface
    public interface Gate {
        boolean allows(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder);
    }

    /**
     * The ceiling a consumer answers for an entity, in whole percent points, given the one the library
     * would use ({@code libraryCeiling}, the entity's {@value #CEILING_STAT_ID}); answering
     * {@code libraryCeiling} back keeps it.
     */
    @FunctionalInterface
    public interface Ceiling {
        double percentPoints(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
                double libraryCeiling);
    }

    @Nullable
    private static volatile Gate gate;

    @Nullable
    private static volatile Ceiling ceiling;

    /**
     * Install the one rule asked before any resistance counts for an entity: while it answers false, the
     * entity carries none of these resistances, worn armor's own untouched. Null removes it. Set once, at
     * setup.
     */
    public static void installGate(@Nullable Gate rule) {
        gate = rule;
    }

    /**
     * Install the one rule that answers the ceiling instead of the {@value #CEILING_STAT_ID} stat (a
     * consumer's own safety rail, tunable where its owner tunes the rest). Null removes it. Set once, at
     * setup.
     */
    public static void installCeiling(@Nullable Ceiling rule) {
        ceiling = rule;
    }

    /** Whether resistance counts for {@code holder} now: the installed rule's answer, else true. */
    public static boolean allows(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder) {
        Gate rule = gate;
        return rule == null || rule.allows(accessor, holder);
    }

    /**
     * The ceiling for {@code holder}, whole percent points in {@code [0, HIGHEST_PERCENT]}: the installed rule's
     * answer over the entity's own {@value #CEILING_STAT_ID}, or that stat alone; the shipped asset's
     * {@code Max} (else its fail-closed mirror) when the stat is not on the entity yet.
     */
    public static double ceilingPercent(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull EntityStatMap stats) {
        int idx = StatIndexCache.resolve(CEILING_STAT_ID);
        double library = idx >= 0 && stats.get(idx) != null
                ? TypedDamageBonuses.readMax(stats, CEILING_STAT_ID)
                : assetCeilingPercent();
        Ceiling rule = ceiling;
        double answered = rule == null ? library : rule.percentPoints(accessor, holder, library);
        return heldShortOfFull(Double.isFinite(answered) ? answered : library);
    }

    /**
     * The ceiling with no entity in hand (a page or an audit naming it): the {@value #CEILING_STAT_ID}
     * asset's own {@code Max}, or {@link #FALLBACK_CEILING_PERCENT} (its mirror, fail-closed) when no such
     * asset is loaded.
     */
    public static double assetCeilingPercent() {
        try {
            int idx = StatIndexCache.resolve(CEILING_STAT_ID);
            EntityStatType asset = idx < 0 ? null : EntityStatType.getAssetMap().getAsset(idx);
            return asset == null ? FALLBACK_CEILING_PERCENT : heldShortOfFull(asset.getMax());
        } catch (Throwable t) {
            return FALLBACK_CEILING_PERCENT;
        }
    }

    /** {@code statId}'s current maximum on {@code stats} (where modifiers accumulate), 0 when unknown. */
    public static double pointsOf(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        return TypedDamageBonuses.readMax(stats, statId);
    }

    /**
     * Count {@code statId} (whole percent points) toward the resistance to {@code typeId} (a resistance type,
     * ignoring case, or {@link #EVERY_TYPE} for every damage type at once), held through {@code hold} when one
     * is given: a consumer's own stat, or a retired one it keeps applying (only what it adds, never a second copy
     * of the library's own {@code DamageResist_<Type>}). The same stat for the same type again replaces the
     * earlier registration, so a late re-entry is safe. Set at setup, before the stores load: the type is matched
     * when a reading asks for it, so a type whose files never load is never read.
     */
    public static void registerSource(@Nonnull String typeId, @Nonnull String statId, @Nullable DoubleUnaryOperator hold) {
        if (typeId.isBlank() || statId.isBlank()) {
            return;
        }
        List<Source> list = SOURCES.computeIfAbsent(typeId.toLowerCase(Locale.ROOT), k -> new CopyOnWriteArrayList<>());
        list.removeIf(s -> s.statId().equalsIgnoreCase(statId));
        list.add(new Source(statId, hold));
    }

    /**
     * What {@code stats} asks of the resistance to {@code typeId}, whole percent points, before any ceiling:
     * the type's own {@code DamageResist_<Type>} plus every source registered for it (each through its hold),
     * and, for a damage type, {@value #EVERY_TYPE_STAT_ID} and every {@link #EVERY_TYPE} source. 0 for a type the
     * loaded files name no resistance for. A source reading zero costs one stat read.
     */
    public static double pointsFor(@Nonnull EntityStatMap stats, @Nonnull String typeId) {
        String type = canonicalType(typeId);
        if (type == null) {
            return 0.0;
        }
        double points = pointsOf(stats, PREFIX + type) + sourcePoints(stats, SOURCES.get(type.toLowerCase(Locale.ROOT)));
        if (DamageTypes.bonusType(type) != null) {
            points += pointsOf(stats, EVERY_TYPE_STAT_ID) + sourcePoints(stats, SOURCES.get(EVERY_TYPE));
        }
        return points;
    }

    /** {@code sources}' readings off {@code stats}, each through its hold; 0 for none. */
    private static double sourcePoints(@Nonnull EntityStatMap stats, @Nullable List<Source> sources) {
        if (sources == null) {
            return 0.0;
        }
        double points = 0.0;
        for (Source source : sources) {
            double read = pointsOf(stats, source.statId());
            if (read == 0.0) {
                continue;
            }
            double held = source.hold() == null ? read : source.hold().applyAsDouble(read);
            if (Double.isFinite(held)) {
                points += held;
            }
        }
        return points;
    }

    /** Whether {@code statId} is a registered source of any type's resistance (ignoring case). */
    public static boolean isSource(@Nullable String statId) {
        if (statId == null) {
            return false;
        }
        for (List<Source> list : SOURCES.values()) {
            for (Source source : list) {
                if (source.statId().equalsIgnoreCase(statId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The resistance stat for {@code typeId} (matched ignoring case), or null when it names no type. */
    @Nullable
    public static String statId(@Nullable String typeId) {
        String type = canonicalType(typeId);
        return type == null ? null : PREFIX + type;
    }

    /** The type {@code statId} resists (ignoring case), or null when it is no resistance stat. */
    @Nullable
    public static String typeOfStat(@Nullable String statId) {
        if (statId == null || !statId.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        return canonicalType(statId.substring(PREFIX.length()));
    }

    /**
     * Whether a write to {@code statId} can change what an entity resists: a {@code DamageResist_<Type>}
     * stat, {@value #EVERY_TYPE_STAT_ID}, a registered source of one ({@link #registerSource}) or the ceiling. A
     * consumer writing one by hand asks this before it syncs the entity.
     */
    public static boolean affectsResistance(@Nullable String statId) {
        return typeOfStat(statId) != null || EVERY_TYPE_STAT_ID.equalsIgnoreCase(statId)
                || CEILING_STAT_ID.equalsIgnoreCase(statId) || isSource(statId);
    }

    /** {@code typeId} in its resistance-type spelling (matched ignoring case, {@link #types}), or null. */
    @Nullable
    public static String canonicalType(@Nullable String typeId) {
        return DamageTypes.resistType(typeId);
    }

    private static double heldShortOfFull(double percent) {
        return Math.max(0.0, Math.min(HIGHEST_PERCENT, percent));
    }
}
