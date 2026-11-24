package com.ziggfreed.common.damage.pvp;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.CeilingTags;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The PvP stats and the reads the PvP hit step ({@link PvpHitSystem}) makes of them. Each stat is an ordinary native
 * entity stat the library ships as JSON, read off its maximum, so gear, an effect or a talent's grant raises it with no
 * code: {@value #RESIST} (whole percent less damage from other players' hits, held at its file's {@code ZigCeiling}),
 * {@value #KNOCKBACK_RESIST} (whole percent less knockback from them, held the same way) and {@value #BOUNTY_BONUS}
 * (whole percent more on a bounty its carrier ends, read by zc-world's PvP ledger). None of them is read on a hit from a
 * creature, a fall or anything that is no player, so a PvP perk never touches the rest of the game.
 */
public final class PvpHits {

    /** Whole percent less damage from other players. */
    public static final String RESIST = "PvpResist";

    /** Whole percent less knockback from other players' hits. */
    public static final String KNOCKBACK_RESIST = "PvpKnockbackResist";

    /** Whole percent more on a bounty the carrier ends. */
    public static final String BOUNTY_BONUS = "PvpBountyBonus";

    /** The hit's meta keys, held apart so reading a stat's share never loads the engine's damage class. */
    static final class Meta {

        /**
         * The victim's player id on a hit one player deals another, noted in the Gather group
         * ({@link PvpHitSystem.Stamp}) before any step reads the hit, so a hit-aware condition (a grudge) can ask who
         * it lands on.
         */
        @Nonnull
        static final MetaKey<UUID> VICTIM_ID = Damage.META_REGISTRY.registerMetaObject();

        private Meta() {
        }
    }

    /** {@value #RESIST}'s cap when its file names none: the number the file ships. */
    static final double RESIST_CAP = 25.0;

    /** {@value #KNOCKBACK_RESIST}'s cap when its file names none: the number the file ships. */
    static final double KNOCKBACK_CAP = 50.0;

    private PvpHits() {
    }

    /**
     * The share of a hit (or of its knockback) a cut of {@code points} whole percent lets through, the points held
     * between 0 and {@code cap}: 1 for none, a negative or one that is not a number. Pure.
     */
    public static double through(double points, double cap) {
        if (!Double.isFinite(points) || points <= 0.0) {
            return 1.0;
        }
        double held = Double.isFinite(cap) ? Math.min(points, Math.max(0.0, cap)) : points;
        return Math.max(0.0, 1.0 - Math.min(100.0, held) / 100.0);
    }

    /** The share of a player's hit {@code victim}'s {@value #RESIST} lets through, held at its cap. World thread. */
    static double damageThrough(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> victim) {
        return through(points(accessor, victim, RESIST), CeilingTags.ceilingOf(RESIST, RESIST_CAP));
    }

    /** The share of a player's knockback {@code victim}'s {@value #KNOCKBACK_RESIST} lets through. World thread. */
    static double knockbackThrough(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> victim) {
        return through(points(accessor, victim, KNOCKBACK_RESIST),
                CeilingTags.ceilingOf(KNOCKBACK_RESIST, KNOCKBACK_CAP));
    }

    /**
     * What the entity at {@code ref} reads on {@code statId}, off its maximum (gear, effects and grants included); 0
     * when it carries no such stat or it is not a number. World thread; never throws.
     */
    public static double points(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId) {
        try {
            int index = StatIndexCache.resolveCurrent(statId);
            if (index == StatIndexCache.UNRESOLVED || !ref.isValid()) {
                return 0.0;
            }
            EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
            EntityStatValue value = stats == null ? null : stats.get(index);
            double max = value == null ? 0.0 : value.getMax();
            return Double.isFinite(max) ? max : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * The player a hit one player deals another lands on, by id, or null for any other hit (on a creature, from a
     * creature, a fall). Read on any step after the Gather group.
     */
    @Nullable
    public static UUID victimOf(@Nullable Damage damage) {
        try {
            return damage == null ? null : damage.getIfPresentMetaObject(Meta.VICTIM_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The player who dealt {@code damage} to the player at {@code victim}: the hit's entity source (a blow, the
     * shooter of a projectile, whoever put a damage over time on), when that is a player and not the victim; null for
     * any other hit, a hit on a creature included. World thread; never throws.
     */
    @Nullable
    public static Ref<EntityStore> playerAttacker(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Damage damage, @Nonnull Ref<EntityStore> victim) {
        try {
            if (!(damage.getSource() instanceof Damage.EntitySource source)) {
                return null;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid() || Nearby.same(attacker, victim)
                    || accessor.getComponent(attacker, PlayerRef.getComponentType()) == null
                    || accessor.getComponent(victim, PlayerRef.getComponentType()) == null) {
                return null;
            }
            return attacker;
        } catch (Throwable t) {
            return null;
        }
    }
}
