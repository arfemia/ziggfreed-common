package com.ziggfreed.common.instance.effect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One rung of an {@link EffectBandLadder}: a band index paired with the
 * {@code EntityEffect} id (a string asset id) to apply when a tracked value snaps
 * to this band. Immutable, mod-agnostic, config-free: the consumer supplies the id.
 *
 * <p>A {@code null} or blank {@link #effectId()} is the "clear / no effect" rung
 * (the baseline band, e.g. the 1.0x speed rung that leaves the role's authored
 * {@code MaxSpeed} untouched). {@link #oneShot()} is advisory metadata for a
 * consumer that distinguishes a timed one-shot apply from a persistent band swap
 * (the ladder itself does not act on it).
 *
 * @param bandIndex this rung's position in the ladder (0-based)
 * @param effectId  the EntityEffect asset id to apply at this band, or
 *                  {@code null}/blank for "no effect" (baseline / clear)
 * @param oneShot   advisory: true if this band's effect is meant as a timed
 *                  one-shot rather than a persistent swap
 */
public record EffectBand(int bandIndex, @Nullable String effectId, boolean oneShot) {

    /** @return true when this band carries an actual effect id (non-null, non-blank). */
    public boolean hasEffect() {
        return effectId != null && !effectId.isBlank();
    }

    /** A persistent (non one-shot) band with the given id (null/blank = baseline). */
    @Nonnull
    public static EffectBand persistent(int bandIndex, @Nullable String effectId) {
        return new EffectBand(bandIndex, effectId, false);
    }

    /** A baseline (no-effect) band at the given index. */
    @Nonnull
    public static EffectBand baseline(int bandIndex) {
        return new EffectBand(bandIndex, null, false);
    }
}
