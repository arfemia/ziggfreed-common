package com.ziggfreed.common.health;

import java.time.Duration;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

/**
 * Health and mana regeneration as stats (R706): {@value #HEALTH_STAT} and {@value #MANA_STAT}, each a percent of the
 * carrier's maximum regained every second, in percent points (0.5 is half a percent a second), raised by anything
 * that raises a stat (an item's {@code StatModifiers}, an effect's {@code RawStatModifiers}, a pick's grant, a mod's
 * keyed modifier). Both ship from zc-entity ({@code Server/Entity/Stats/}). {@link StatRegenSystem} is their one
 * reader and payer, every tick, for every living player.
 *
 * <p>Health regeneration pauses for {@link #HEALTH_PAUSE} after any hit the player takes, read off the engine's own
 * {@code DamageDataComponent} clock: the same fifteen seconds the engine's {@code NoDamageTaken} condition gives NPC
 * regeneration, so a well-built character heals between fights rather than out-healing a mob in one. Mana is never
 * paused, since it is what an ability spends. Native regeneration has the pause but no per-player amount, which is
 * why the amount is a stat and the payer is ours.
 *
 * <p>The arithmetic is pure ({@link #amountFor}), so it is the same number a tooltip would show.
 */
public final class StatRegen {

    /** Health regained each second, percent points of maximum Health. */
    public static final String HEALTH_STAT = "Regen_Health";

    /** Mana regained each second, percent points of maximum Mana. */
    public static final String MANA_STAT = "Regen_Mana";

    /** How long after taking a hit health regeneration stays off. */
    public static final Duration HEALTH_PAUSE = Duration.ofSeconds(15);

    /** Not resolved yet, or not loaded (the engine's own missing index). */
    private static final int UNRESOLVED = Integer.MIN_VALUE;

    private static volatile int healthIndex = UNRESOLVED;
    private static volatile int manaIndex = UNRESOLVED;

    private StatRegen() {
    }

    /**
     * What one tick of {@code dt} seconds regains on a stat whose maximum is {@code max}, at {@code percentPerSecond}
     * percent points a second; 0 for a total at or below 0, a maximum at or below 0, or anything not a number.
     */
    public static float amountFor(float max, double percentPerSecond, float dt) {
        if (!(percentPerSecond > 0.0) || !(max > 0f) || !(dt > 0f) || !Double.isFinite(percentPerSecond)) {
            return 0f;
        }
        return (float) (max * (percentPerSecond / 100.0) * dt);
    }

    /** {@code stats}' total of the health regeneration stat, 0 when it is not loaded. */
    static double healthPoints(@Nonnull EntityStatMap stats) {
        int index = healthIndex;
        if (index < 0) {
            index = healthIndex = resolve(HEALTH_STAT);
        }
        return pointsAt(stats, index);
    }

    /** {@code stats}' total of the mana regeneration stat, 0 when it is not loaded. */
    static double manaPoints(@Nonnull EntityStatMap stats) {
        int index = manaIndex;
        if (index < 0) {
            index = manaIndex = resolve(MANA_STAT);
        }
        return pointsAt(stats, index);
    }

    private static double pointsAt(@Nonnull EntityStatMap stats, int index) {
        if (index < 0) {
            return 0.0;
        }
        EntityStatValue value = stats.get(index);
        return value == null ? 0.0 : value.getMax();
    }

    /** The stat's index, or {@link #UNRESOLVED} (never cached as resolved) when it is not loaded yet. */
    private static int resolve(@Nonnull String statId) {
        try {
            int index = EntityStatType.getAssetMap().getIndex(statId);
            return index >= 0 ? index : UNRESOLVED;
        } catch (Throwable t) {
            return UNRESOLVED;
        }
    }
}
