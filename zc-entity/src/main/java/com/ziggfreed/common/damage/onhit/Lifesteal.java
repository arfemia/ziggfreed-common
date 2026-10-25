package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.damage.RoutedStats;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * Lifesteal (R866): one percent {@value #STAT} stat any attacker reads, a creature as much as a player (R860), whole
 * percent points off the stat's maximum. {@link LifestealSystem} heals the attacker that share of every direct hit it
 * lands, once the hit is applied, through zc-core's one healing applier as a drain (no healer, so healing power never
 * raises it and no heal-given moment fires), and announces it ({@link ZigLifestealEvent}). A consumer counts a stat of
 * its own on the hits its route names ({@link #registerSource}: a weapon skill's own share), and one consumer rule may
 * refuse a hit the step ({@link #installHitRule}). A share at or past 100 heals the whole hit, a semantic bound.
 */
public final class Lifesteal {

    /** The share of a hit healed back, whole percent points (5 heals a twentieth of the hit). Base 0. */
    public static final String STAT = "Lifesteal";

    private static final RoutedStats SOURCES = new RoutedStats();

    @Nullable
    private static volatile HitRule hitRule;

    private Lifesteal() {
    }

    /**
     * Count {@code statId} (whole percent points) toward lifesteal on the hits {@code route} names (every hit when
     * null): only what a consumer adds over {@value #STAT}. The same stat again replaces it. Set at setup.
     */
    public static void registerSource(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
        SOURCES.register(statId, route);
    }

    /**
     * Install the one consumer rule asked before a hit may heal its attacker (a tick of the consumer's own that takes
     * nothing); null removes it, and with none every hit may. Set once, at setup.
     */
    public static void installHitRule(@Nullable HitRule rule) {
        hitRule = rule;
    }

    /** Does the installed rule let this hit heal (true with none; a rule that throws refuses)? */
    static boolean ruleAllows(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull Damage damage) {
        HitRule rule = hitRule;
        if (rule == null) {
            return true;
        }
        try {
            return rule.allows(store, attacker, target, damage);
        } catch (Throwable t) {
            SafeLog.fine("[lifesteal] the hit rule failed: " + t.getMessage());
            return false;
        }
    }

    /** The attacker's lifesteal on this hit, whole percent points: {@value #STAT} and every source its route names. */
    public static double points(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull EntityStatMap stats) {
        double own = TypedDamageBonuses.readMax(stats, STAT);
        return SOURCES.anyOn(stats) ? own + SOURCES.points(accessor, attacker, damage, stats) : own;
    }

    /** The health a hit of {@code amount} heals back at {@code points}: never below 0, never past the hit. Pure. */
    public static double healFor(double amount, double points) {
        if (!Double.isFinite(amount) || !Double.isFinite(points) || amount <= 0.0 || points <= 0.0) {
            return 0.0;
        }
        return amount * Math.min(1.0, points / 100.0);
    }
}
