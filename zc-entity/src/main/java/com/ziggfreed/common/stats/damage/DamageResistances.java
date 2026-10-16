package com.ziggfreed.common.stats.damage;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;

/**
 * Per-damage-type resistance, read from stats any gear can raise: for each type
 * {@link TypedDamageBonuses#TYPE_IDS} names, the library ships {@code DamageResist_<Type>} (whole percent
 * points, 10 resists a tenth of each hit of the type), so an item's native {@code StatModifiers} on a server
 * running this library and nothing else make its wearer or wielder take less of that type. Two items
 * carrying one add up, as any stat's modifiers do.
 *
 * <p><b>The engine applies it.</b> The engine reads resistance only from worn armor and active effects,
 * never from a stat, so zc-effects' {@code DamageResistReconciler} keeps one hidden effect per type on each
 * player whose native {@code DamageResistance} equals that type's stat, and the engine sums it with worn
 * armor. Nothing reads these stats at a damage site: a fold that did would mitigate the same points twice.
 * This class is the stat family: its ids, its ceiling and the two consumer rules over it.
 *
 * <p><b>The ceiling</b> is the {@value #CEILING_STAT_ID} stat, read off the entity like any stat: its asset's
 * own {@code Max} is the ceiling every player starts with (content a server retunes by a same-id file), and
 * gear can raise or lower it. It bounds each type on its own. One consumer rule may answer the ceiling
 * instead ({@link #installCeiling}); no resistance passes {@link #FULL_PERCENT}, a whole hit.
 *
 * <p><b>The world rule:</b> one consumer rule may switch every resistance off for an entity
 * ({@link #installGate}, a world that plays plain combat, say); with none installed every one counts.
 */
public final class DamageResistances {

    private DamageResistances() {
    }

    /** The resistance stat id prefix: {@code DamageResist_<Type>}, whole percent points. */
    public static final String PREFIX = "DamageResist_";

    /** The ceiling stat: the most of one type's hits a resistance covers, whole percent points. */
    public static final String CEILING_STAT_ID = "DamageResistMax";

    /** A whole hit: the semantic bound past which a resistance means nothing more. */
    public static final int FULL_PERCENT = 100;

    /**
     * The FALLBACK ceiling, whole percent points, standing in only when the {@value #CEILING_STAT_ID} asset
     * is not loaded (early boot, a unit JVM, a failed load). The shipped asset's {@code Max} is the
     * authority: this constant mirrors it, so a missing asset fails closed toward the shipped bound rather
     * than open to a whole hit, and it moves when the asset moves ({@code DamageResistStatsTest} holds the
     * two equal).
     */
    public static final int FALLBACK_CEILING_PERCENT = 80;

    /** The types the library ships a resistance stat for: the same roster as the damage bonuses. */
    public static final List<String> TYPE_IDS = TypedDamageBonuses.TYPE_IDS;

    /** {@code DamageResist_<Type>} for each of {@link #TYPE_IDS}, in the same order. */
    public static final List<String> STAT_IDS = statIdsOf(TYPE_IDS);

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
     * The ceiling for {@code holder}, whole percent points in {@code [0, FULL_PERCENT]}: the installed rule's
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
        return heldToFull(Double.isFinite(answered) ? answered : library);
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
            return asset == null ? FALLBACK_CEILING_PERCENT : heldToFull(asset.getMax());
        } catch (Throwable t) {
            return FALLBACK_CEILING_PERCENT;
        }
    }

    /** {@code statId}'s current maximum on {@code stats} (where modifiers accumulate), 0 when unknown. */
    public static double pointsOf(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        return TypedDamageBonuses.readMax(stats, statId);
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
     * stat or the ceiling. A consumer writing one by hand asks this before it syncs the entity.
     */
    public static boolean affectsResistance(@Nullable String statId) {
        return typeOfStat(statId) != null || CEILING_STAT_ID.equalsIgnoreCase(statId);
    }

    /** The {@link #TYPE_IDS} spelling of {@code typeId} (matched ignoring case), or null. */
    @Nullable
    public static String canonicalType(@Nullable String typeId) {
        if (typeId == null) {
            return null;
        }
        for (String type : TYPE_IDS) {
            if (type.equalsIgnoreCase(typeId)) {
                return type;
            }
        }
        return null;
    }

    private static double heldToFull(double percent) {
        return Math.max(0.0, Math.min(FULL_PERCENT, percent));
    }

    @Nonnull
    private static List<String> statIdsOf(@Nonnull List<String> types) {
        List<String> out = new ArrayList<>(types.size());
        for (String type : types) {
            out.add(PREFIX + type);
        }
        return List.copyOf(out);
    }
}
