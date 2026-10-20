package com.ziggfreed.common.damage.weakspot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;

/**
 * Weak spots (R628, R683, R708): a bonus that rides on top of a hit where the hit really lands on a weak spot,
 * the attacker's {@value #DAMAGE_STAT} held to their {@value #CEILING_STAT}, paid once per hit however many ways
 * the hit is a weak spot. A hit is a weak spot when it lands inside the target's {@code Head} detail box (the
 * model's own {@code DetailBoxes}), from behind the target (the rear arc the game's own dagger backstab tests),
 * while the target carries a window effect (one tagged {@value #TAG} in its own native {@code Tags}, R751, or one
 * the settings list for vanilla's own: a stun, a flinch's lockout, a freeze, a boss broken down), or on a target at
 * or under the attacker's {@value #FOE_LOW_STAT} percent of its health. {@link WeakSpotSystem} applies it; the
 * settings are {@code Server/ZiggfreedCommon/WeakSpotSettings/Default.json} ({@link WeakSpotSettingsAsset}).
 *
 * <p><b>What a weak spot pays</b> (R757, every number a stat): {@value #DAMAGE_STAT}, plus each way's own stat when the
 * hit is a weak spot that way ({@value #HEAD_STAT} on the head, {@value #BACK_STAT} from behind,
 * {@value #STAGGERED_STAT} in a window), plus every stat a consumer registers as a source ({@link #registerSource}: a
 * weapon skill's own bonus, counted on the hits its route names), the whole held to {@value #CEILING_STAT}.
 *
 * <p><b>Never two weak-spot bonuses.</b> A vanilla weapon whose damage interaction carries a {@code Head}
 * {@code TargetedDamage} (the shortbow's full draw) or a rear {@code AngledDamage} with damage of its own (a dagger's
 * backstab branch) already pays its own bonus through the engine. Such a hit is a weak spot too, and it takes the
 * larger of the two bonuses, never both: ours adds only what it exceeds vanilla's by ({@link #factorOver}), on every
 * damage of the hit, so a weak spot never pays past {@value #CEILING_STAT} on top of a body hit unless vanilla's own
 * branch alone already does.
 *
 * <p>Both stats are whole percent points read off the stat's maximum, where native modifiers accumulate (an
 * item's {@code StatModifiers}, an effect's, a {@code Stat} grant), like every library stat.
 */
public final class WeakSpots {

    /** The weak-spot bonus, whole percent points (25 is +25%). Base 0: nothing is a weak spot until it is raised. */
    public static final String DAMAGE_STAT = "WeakSpotDamage";

    /** The most {@link #DAMAGE_STAT} pays, whole percent points; its asset's Max (75, R708) is the default. */
    public static final String CEILING_STAT = "WeakSpotMax";

    /** More weak-spot bonus on a hit to the head, whole percent points. */
    public static final String HEAD_STAT = "WeakSpotDamage_Head";

    /** More weak-spot bonus on a hit from behind, whole percent points. */
    public static final String BACK_STAT = "WeakSpotDamage_Back";

    /** More weak-spot bonus on a hit inside a window (a stunned, frozen, flinching or launched foe), whole points. */
    public static final String STAGGERED_STAT = "WeakSpotDamage_Staggered";

    /**
     * The health, in percent of its whole, at or under which a target takes the attacker's every hit as a weak spot
     * (8 is a foe at 8% health or less). Base 0: no foe is low enough until it is raised.
     */
    public static final String FOE_LOW_STAT = "WeakSpot_FoeLow";

    /** One registered weak-spot source: a stat counted on the hits its route names. */
    public record Source(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
    }

    /** The registered sources, in registration order. */
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    /** The default ceiling, mirroring the shipped {@code WeakSpotMax.json}'s Max, for when the stat cannot be read. */
    public static final double FALLBACK_CEILING_PERCENT = 75.0;

    /**
     * The native {@code Tags} key an effect carries to open a weak-spot window on whoever has it:
     * {@code "Tags": { "ZigWeakSpot": ["Anyone"] }} for every attacker, {@code ["Owner"]} for the effect's owner
     * only (whoever applied it, {@code ActiveEntityEffect.getOwner}).
     */
    public static final String TAG = "ZigWeakSpot";

    /** The {@link #TAG} value that opens the window for the effect's owner alone. */
    public static final String OWNER_ONLY = "Owner";

    /**
     * The airborne effect (R744): put on a foe your own hit launched (a native {@code ApplyEffect} in the hit's
     * chain sets you as its owner), it opens the window for you alone and comes off when the foe lands
     * ({@code ZigGrounded}).
     */
    public static final String AIRBORNE_EFFECT = "Zig_Airborne";

    /** The hit's meta key, in a holder so the arithmetic below never class-inits the engine's Damage. */
    private static final class Meta {
        static final MetaKey<Float> HIT = Damage.META_REGISTRY.registerMetaObject();
    }

    private WeakSpots() {
    }

    /**
     * The key {@link WeakSpotSystem} puts on a hit it paid as a weak spot: the bonus it added, as a fraction (0.5 is
     * +50%; 0 for a weak spot vanilla's own headshot already paid in full). A later step reads it to know the hit
     * was a weak spot.
     */
    @Nonnull
    public static MetaKey<Float> hitKey() {
        return Meta.HIT;
    }

    /** Was {@code damage} paid as a weak spot? */
    public static boolean isWeakSpot(@Nullable Damage damage) {
        return damage != null && damage.getIfPresentMetaObject(Meta.HIT) != null;
    }

    /**
     * Did the hit {@code damage} belongs to take its weapon's own {@code Head} branch on {@code target} (the
     * interaction's {@code TargetedDamage} entry keyed {@code Head}: in vanilla, a shortbow's full-draw arrow to the
     * head)? Decided on the hit's first damage and answered the same for its later ones, in either damage group.
     * False when it cannot tell. Never throws.
     */
    public static boolean headBranch(@Nonnull Damage damage, @Nonnull Ref<EntityStore> target) {
        VanillaWeakSpots.Paid paid = VanillaWeakSpots.paidForHit(damage, target);
        return paid != null && paid.head();
    }

    /**
     * The weak-spot bonus as a fraction: {@code points} held to [0, {@code ceiling}] (both whole percent points),
     * the ceiling itself never below 0. 0 for a value that is not a number.
     */
    public static double bonusFraction(double points, double ceiling) {
        if (!Double.isFinite(points) || points <= 0.0) {
            return 0.0;
        }
        double top = Double.isFinite(ceiling) ? Math.max(0.0, ceiling) : FALLBACK_CEILING_PERCENT;
        return Math.min(points, top) / 100.0;
    }

    /**
     * What to multiply a hit by so its weak-spot bonus is the larger of ours ({@code ours}) and the one the hit
     * already carries from vanilla's own headshot ({@code vanilla}), both fractions: {@code (1 + ours) / (1 + vanilla)}
     * when ours is larger, else 1. Never below 1, so a weak spot never takes away what vanilla paid.
     */
    public static double factorOver(double ours, double vanilla) {
        double paid = Double.isFinite(vanilla) ? Math.max(0.0, vanilla) : 0.0;
        double want = Double.isFinite(ours) ? Math.max(0.0, ours) : 0.0;
        return want > paid ? (1.0 + want) / (1.0 + paid) : 1.0;
    }

    /** The attacker's weak-spot bonus in whole points, off the stat's maximum; 0 when it is not on the entity. */
    public static double damagePoints(@Nonnull EntityStatMap stats) {
        return readMax(stats, DAMAGE_STAT, 0.0);
    }

    /** A stat's reading in whole points, off its maximum; 0 when it is not on the entity. */
    public static double pointsOf(@Nonnull EntityStatMap stats, @Nonnull String statId) {
        return readMax(stats, statId, 0.0);
    }

    /**
     * Count {@code statId} (whole percent points) toward every weak spot of its holder on the hits {@code route}
     * names (every hit when null): a weapon skill's own weak-spot bonus, counted while its weapon counts. The same
     * stat again replaces the earlier registration. Set at setup.
     */
    public static void registerSource(@Nonnull String statId, @Nullable TypedDamageBonuses.Route route) {
        if (statId.isBlank()) {
            return;
        }
        SOURCES.removeIf(s -> s.statId().equalsIgnoreCase(statId));
        SOURCES.add(new Source(statId, route));
    }

    /** Does the attacker hold any registered source's stat above 0 (before any route is asked)? */
    public static boolean anySource(@Nonnull EntityStatMap stats) {
        for (Source source : SOURCES) {
            if (readMax(stats, source.statId(), 0.0) > 0.0) {
                return true;
            }
        }
        return false;
    }

    /** The registered sources' points on this hit: each nonzero one whose route names it. */
    public static double sourcePoints(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> attacker, @Nonnull Damage damage, @Nonnull EntityStatMap stats) {
        double sum = 0.0;
        for (Source source : SOURCES) {
            double points = readMax(stats, source.statId(), 0.0);
            if (points == 0.0 || (source.route() != null && !source.route().matches(accessor, attacker, damage))) {
                continue;
            }
            sum += points;
        }
        return sum;
    }

    /**
     * The attacker's ceiling in whole points: their own {@value #CEILING_STAT}, else the shipped asset's Max, else
     * {@link #FALLBACK_CEILING_PERCENT}.
     */
    public static double ceilingPercent(@Nonnull EntityStatMap stats) {
        return readMax(stats, CEILING_STAT, assetCeilingPercent());
    }

    /** The ceiling with no entity in hand: the asset's own Max, or {@link #FALLBACK_CEILING_PERCENT}. */
    public static double assetCeilingPercent() {
        try {
            int idx = StatIndexCache.resolve(CEILING_STAT);
            EntityStatType asset = idx < 0 ? null : EntityStatType.getAssetMap().getAsset(idx);
            return asset == null ? FALLBACK_CEILING_PERCENT : asset.getMax();
        } catch (Throwable t) {
            return FALLBACK_CEILING_PERCENT;
        }
    }

    private static double readMax(@Nonnull EntityStatMap stats, @Nonnull String statId, double fallback) {
        int idx = StatIndexCache.resolve(statId);
        if (idx < 0) {
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
