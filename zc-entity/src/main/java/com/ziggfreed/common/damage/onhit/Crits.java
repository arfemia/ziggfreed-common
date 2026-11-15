package com.ziggfreed.common.damage.onhit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.BoundGrants;
import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.stats.damage.DamageTypes;
import com.ziggfreed.common.stats.damage.RoutedStats;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * Critical hits (R865): one {@value #CHANCE_STAT} and one {@value #DAMAGE_STAT} stat every attacker reads, a creature
 * as much as a player (R860), both whole percent points off the stat's maximum, where gear, effects and grants
 * accumulate. {@link CritSystem} rolls the chance once per direct hit, after the weak-spot step, and a critical hit
 * deals one plus the crit damage times as much ({@value #DAMAGE_STAT} ships at 50: half again), shows the attacker its
 * word ({@code WeakSpotSettings}' {@code CritText}) and fires {@link ZigCritEvent} (zc-track's Crit procs hear it).
 *
 * <p><b>What the chance adds up</b>: {@value #CHANCE_STAT}, every conditional stat whose file pays {@code CritChance}
 * and counts on this hit ({@code ConditionalStats.critPoints}: a frozen foe, say), and every stat a consumer registers
 * as a source ({@link #registerChanceSource}: a weapon skill's own share, counted where its line counts). The crit
 * damage adds {@value #DAMAGE_STAT} and every {@link #registerDamageSource}. Each library stat (the two and the hit
 * type's own) also takes the bound grants on it that are off but hold on this hit ({@code BoundGrants.hitPoints}, R945:
 * a weapon talent on a hit that earns its skill with another item in hand). A chance at or past 100 crits every hit,
 * a semantic bound and no balance number. One consumer rule may refuse a hit the step ({@link #installHitRule}).
 *
 * <p><b>The word</b> shows only to the attacker (R896), and only while the owner's {@code CritText} is on and the
 * attacker's own choice wants it ({@link #installWordRule}: the wiring root hands over the player's Settings switch).
 */
public final class Crits {

    /** The critical chance, whole percent points (10 is a 10% chance). Base 0. */
    public static final String CHANCE_STAT = "CritChance";

    /** How much more a critical hit deals, whole percent points of the hit (50 is half again). Base 50. */
    public static final String DAMAGE_STAT = "CritDamage";

    /**
     * The FALLBACK crit damage, whole percent points, standing in only when the {@value #DAMAGE_STAT} stat cannot be
     * read: it mirrors the shipped asset's base {@code Max} ({@code CritsTest} holds the two equal).
     */
    public static final double FALLBACK_DAMAGE_PERCENT = 50.0;

    /** The word's lang key, in this module's {@code ziggfreedcommon.crit.lang}. */
    static final String TEXT_KEY = "ziggfreedcommon.crit.combattext";

    /** Where the word floats: opposite the weak spot's. */
    static final float TEXT_ANGLE = -20f;

    private static final RoutedStats CHANCE_SOURCES = new RoutedStats();

    private static final RoutedStats DAMAGE_SOURCES = new RoutedStats();

    @Nullable
    private static volatile HitRule hitRule;

    @Nullable
    private static volatile WordRule wordRule;

    private static final AtomicBoolean WORD_FALLBACK_SAID = new AtomicBoolean();

    /** Whether this attacker wants the word over their own critical hits. World thread. */
    @FunctionalInterface
    public interface WordRule {
        boolean shows(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker);
    }

    /** The hit's meta key, in a holder so the arithmetic below never class-inits the engine's Damage. */
    private static final class Meta {
        static final MetaKey<Float> HIT = Damage.META_REGISTRY.registerMetaObject();
    }

    private Crits() {
    }

    /**
     * Count {@code statId} (whole percent points) toward the critical chance on the hits {@code route} names (every hit
     * when null): a consumer's own share, only what it adds over {@value #CHANCE_STAT}. The same stat again replaces
     * it. Set at setup.
     */
    public static void registerChanceSource(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
        CHANCE_SOURCES.register(statId, route);
    }

    /** {@link #registerChanceSource}, toward the crit damage. */
    public static void registerDamageSource(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
        DAMAGE_SOURCES.register(statId, route);
    }

    /**
     * Install the one consumer rule asked before a hit may crit (an ability that never crits, a tick of the consumer's
     * own); null removes it, and with none every hit may. Set once, at setup.
     */
    public static void installHitRule(@Nullable HitRule rule) {
        hitRule = rule;
    }

    /**
     * Install the attacker's own choice over the word (the player's Settings switch, which the wiring root hands over);
     * null removes it. Set once, at setup.
     */
    public static void installWordRule(@Nullable WordRule rule) {
        wordRule = rule;
    }

    /** Does the attacker want the word? The installed rule, else yes (said once); a rule that throws shows it. */
    static boolean wordWanted(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker) {
        WordRule rule = wordRule;
        if (rule == null) {
            if (WORD_FALLBACK_SAID.compareAndSet(false, true)) {
                SafeLog.info("[crit] nothing hands over each player's choice of the critical hit word, so every "
                        + "attacker sees it while CritText is on");
            }
            return true;
        }
        try {
            return rule.shows(store, attacker);
        } catch (Throwable t) {
            SafeLog.fine("[crit] the word rule failed: " + t.getMessage());
            return true;
        }
    }

    /** Does the installed rule let this hit crit (true with none; a rule that throws refuses)? */
    static boolean ruleAllows(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull Damage damage) {
        HitRule rule = hitRule;
        if (rule == null) {
            return true;
        }
        try {
            return rule.allows(store, attacker, target, damage);
        } catch (Throwable t) {
            SafeLog.fine("[crit] the hit rule failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Could the attacker crit this hit at all: a chance stat, a source, a conditional stat or the hit type's own chance
     * stat off 0, or a bound grant a hit may pay ({@link BoundGrants#hasHitTerms})? Cheap; asked first.
     */
    static boolean mayCrit(@Nonnull Ref<EntityStore> attacker, @Nonnull EntityStatMap stats, @Nonnull Damage damage) {
        return pointsOf(stats, CHANCE_STAT, 0.0) != 0.0 || CHANCE_SOURCES.anyOn(stats)
                || ConditionalStats.isHolder(attacker) || pointsAt(stats, typeStats(damage).chance()) != 0.0
                || BoundGrants.hasHitTerms(attacker);
    }

    /**
     * The attacker's critical chance on this hit, whole percent points, before any bound: {@value #CHANCE_STAT} and the
     * hit type's own, each with the off bound grants that hold on this hit ({@link BoundGrants#hitPoints}), the sources
     * and the conditional stats.
     */
    public static double chancePoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nullable Ref<EntityStore> target, @Nonnull Damage damage,
            @Nonnull EntityStatMap stats) {
        int typeChance = typeStats(damage).chance();
        return pointsOf(stats, CHANCE_STAT, 0.0) + pointsAt(stats, typeChance)
                + BoundGrants.hitPoints(accessor, attacker, CHANCE_STAT, damage)
                + BoundGrants.hitPoints(accessor, attacker, typeChance, damage)
                + CHANCE_SOURCES.points(accessor, attacker, damage, stats)
                + ConditionalStats.critPoints(accessor, attacker, target, damage);
    }

    /**
     * The attacker's crit damage on this hit, whole percent points: {@value #DAMAGE_STAT} and the hit type's own, each
     * with the off bound grants that hold on this hit, and the sources.
     */
    public static double damagePoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nonnull Damage damage, @Nonnull EntityStatMap stats) {
        int typeDamage = typeStats(damage).damage();
        return pointsOf(stats, DAMAGE_STAT, FALLBACK_DAMAGE_PERCENT) + pointsAt(stats, typeDamage)
                + BoundGrants.hitPoints(accessor, attacker, DAMAGE_STAT, damage)
                + BoundGrants.hitPoints(accessor, attacker, typeDamage, damage)
                + DAMAGE_SOURCES.points(accessor, attacker, damage, stats);
    }

    /**
     * The hit type's own crit stats (R936): {@value #CHANCE_STAT}{@code _<Type>} and {@value #DAMAGE_STAT}{@code _<Type>}
     * for the hit's cause, else the nearest cause it inherits from that has one, so a school's talent crits only its
     * element's hits. Shipped only for the types something grants; one map read a hit, rebuilt when a stat or cause
     * load moves {@link DamageTypes#generation}.
     */
    @Nonnull
    static TypeStats typeStats(@Nonnull Damage damage) {
        long generation = DamageTypes.generation();
        if (generation != typeStatsGeneration) {
            TYPE_STATS.clear();
            typeStatsGeneration = generation;
        }
        return TYPE_STATS.computeIfAbsent(damage.getDamageCauseIndex(), Crits::resolveTypeStats);
    }

    /** The two stat indices a hit of the cause at {@code causeIndex} reads, {@link StatIndexCache#UNRESOLVED} for none. */
    @Nonnull
    private static TypeStats resolveTypeStats(int causeIndex) {
        int chance = StatIndexCache.UNRESOLVED;
        int damage = StatIndexCache.UNRESOLVED;
        try {
            DamageCause cause = DamageCause.getAssetMap().getAsset(causeIndex);
            for (int depth = 0; cause != null && depth < 8; depth++) {
                if (chance == StatIndexCache.UNRESOLVED) {
                    chance = StatIndexCache.resolve(CHANCE_STAT + "_" + cause.getId());
                }
                if (damage == StatIndexCache.UNRESOLVED) {
                    damage = StatIndexCache.resolve(DAMAGE_STAT + "_" + cause.getId());
                }
                String parent = cause.getInherits();
                cause = parent == null || parent.isBlank() ? null : DamageCause.getAssetMap().getAsset(parent);
            }
        } catch (Throwable t) {
            SafeLog.fine("[crit] a hit type's crit stats could not be read: " + t.getMessage());
        }
        return new TypeStats(chance, damage);
    }

    /** A hit type's two crit stat indices. */
    record TypeStats(int chance, int damage) {
    }

    private static final Map<Integer, TypeStats> TYPE_STATS = new ConcurrentHashMap<>();

    private static volatile long typeStatsGeneration = Long.MIN_VALUE;

    /** A stat's reading off its maximum at {@code index}, 0 for none, an absent stat or one that is not a number. */
    private static double pointsAt(@Nonnull EntityStatMap stats, int index) {
        if (index == StatIndexCache.UNRESOLVED) {
            return 0.0;
        }
        EntityStatValue value = stats.get(index);
        double max = value == null ? 0.0 : value.getMax();
        return Double.isFinite(max) ? max : 0.0;
    }

    /** {@code points} of critical chance as a probability in {@code [0, 1]}; 0 for one that is not a number. Pure. */
    public static double chance(double points) {
        if (!Double.isFinite(points) || points <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, points / 100.0);
    }

    /** What a critical hit is multiplied by for {@code points} of crit damage: one plus it, never below one. Pure. */
    public static double multiplier(double points) {
        if (!Double.isFinite(points)) {
            return 1.0 + FALLBACK_DAMAGE_PERCENT / 100.0;
        }
        return Math.max(1.0, 1.0 + points / 100.0);
    }

    /**
     * The key the crit step puts on a hit it made critical: what it multiplied the hit by. A later step reads it to know
     * the hit was critical.
     */
    @Nonnull
    public static MetaKey<Float> hitKey() {
        return Meta.HIT;
    }

    /** Was {@code damage} a critical hit? */
    public static boolean isCrit(@Nullable Damage damage) {
        return damage != null && damage.getIfPresentMetaObject(Meta.HIT) != null;
    }

    /** A stat's reading off its maximum, {@code fallback} when it is not on the entity or not a number. */
    private static double pointsOf(@Nonnull EntityStatMap stats, @Nonnull String statId, double fallback) {
        int idx = StatIndexCache.resolve(statId);
        if (idx == StatIndexCache.UNRESOLVED) {
            return fallback;
        }
        EntityStatValue value = stats.get(idx);
        if (value == null) {
            return fallback;
        }
        double max = value.getMax();
        return Double.isFinite(max) ? max : fallback;
    }
}
