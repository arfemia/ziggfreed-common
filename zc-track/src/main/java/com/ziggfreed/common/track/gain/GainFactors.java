package com.ziggfreed.common.track.gain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The open registry of gain factors, by id (matched without regard to case): the seam a consumer adds a factor
 * through (a class multiplier, a gear bonus, a permission level cap), and the one the boosts fill. A track runs
 * the factors its {@code Gain.Factors} names, in that order ({@link #DEFAULT_ORDER} when it names none), the ones
 * its {@code Gain.Additive} names adding together ({@link #DEFAULT_ADDITIVE} when it names none); an id nobody
 * registered changes nothing.
 *
 * <p>Ships filled (a zc-only server needs no consumer): {@value #WORLD} reads the world's track rules
 * ({@code world.TrackWorldRules}), {@value #BONUS} reads the player's gain stats ({@link BonusGainFactor}, R734; a
 * consumer adds its own stats to it through {@link TrackGainStats#registerSource}, never by registering over it),
 * {@value #BOOST} holds the neutral factor until the boost module registers over it at setup
 * ({@code boost.BoostBootstrap}), {@value #PRESTIGE} pays a prestiged track's constant extra (R642) and
 * {@value #RESTED} spends a track's rested pool where its {@code Gain.Rested} group turns one on (off on every
 * track until written). {@value #CLASS} is a consumer's to register. Registering again replaces, and every compiled
 * plan is built again.
 */
public final class GainFactors {

    /** A consumer's class multiplier. */
    public static final String CLASS = "Class";
    /** The world's track rules: its gain switch and multiplier for the track or its category. */
    public static final String WORLD = "World";
    /** The player's gain stats: TrackGain, TrackGain_<Category>, TrackGain_<Track> and a consumer's sources (R734). */
    public static final String BONUS = "Bonus";
    /** The boosts: timed ones a player or the server started, and standing ones (boost.BoostFactor). */
    public static final String BOOST = "Boost";
    /** A prestiged track's constant extra (R642; the track's {@code Prestige.GainPercent}). */
    public static final String PRESTIGE = "Prestige";
    /** The rested pool, spent where the track's Gain.Rested group turns it on. */
    public static final String RESTED = "Rested";

    /**
     * The order a track without a {@code Gain.Factors} list runs: the MMO's award order to date, with the
     * prestige extra after the boosts and before the rested pool, which pays on top of everything before it.
     */
    public static final List<String> DEFAULT_ORDER = List.of(CLASS, WORLD, BONUS, BOOST, PRESTIGE, RESTED);

    /**
     * The factors a track without a {@code Gain.Additive} list adds together rather than multiplying: the bonus
     * and the boosts, as the MMO has always stacked them ({@code bonus + (boost - 1)}).
     */
    public static final List<String> DEFAULT_ADDITIVE = List.of(BONUS, BOOST);

    private static final Map<String, GainFactor> FACTORS = new ConcurrentHashMap<>();

    static {
        FACTORS.put(key(WORLD), WorldGainFactor.INSTANCE);
        FACTORS.put(key(BONUS), BonusGainFactor.INSTANCE);
        FACTORS.put(key(BOOST), GainFactor.NEUTRAL);
        FACTORS.put(key(PRESTIGE), PrestigeGainFactor.INSTANCE);
        FACTORS.put(key(RESTED), RestedFactor.INSTANCE);
    }

    private GainFactors() {
    }

    /**
     * Register {@code factor} under {@code id} (any case), replacing what was there, and build every compiled plan
     * again. A blank id or a null factor is ignored.
     */
    public static void register(@Nullable String id, @Nullable GainFactor factor) {
        if (id == null || id.isBlank() || factor == null) {
            return;
        }
        FACTORS.put(key(id), factor);
        GainPlans.invalidateAll();
    }

    /** The factor registered under {@code id} (any case), or null when none is. */
    @Nullable
    public static GainFactor factor(@Nullable String id) {
        return id == null || id.isBlank() ? null : FACTORS.get(key(id));
    }

    /** Is a factor registered under {@code id}? */
    public static boolean isRegistered(@Nullable String id) {
        return factor(id) != null;
    }

    /** Every registered id, lower-cased and sorted (an audit, an authoring hint). */
    @Nonnull
    public static List<String> ids() {
        return List.copyOf(new TreeSet<>(FACTORS.keySet()));
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
