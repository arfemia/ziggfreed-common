package com.ziggfreed.common.effect.buff;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One buff a {@link TimedBuffSource} shows on a player's native buff bar, for as long as it lasts.
 *
 * @param key      what the buff is, unique within its scope across every registered source (prefix
 *                 it with your mod's id when it could meet another source's); the binding keeps each
 *                 key on one tile while it lasts
 * @param effectId the {@code EntityEffect} that is its tile, or null for a slot of the scope's
 *                 default pool ({@link BuffScope#pool}); an id named here must be dedicated to the
 *                 buff, since the binding takes it off whenever no live buff wants it
 * @param scope    whose buff it is, which picks the default pool
 * @param endsAtMs when it ends, in epoch milliseconds; a buff whose end has passed is not shown
 */
public record TimedBuff(@Nonnull String key, @Nullable String effectId, @Nonnull BuffScope scope, long endsAtMs) {

    /** A buff shown on a slot of its scope's default pool. */
    @Nonnull
    public static TimedBuff onDefault(@Nonnull String key, @Nonnull BuffScope scope, long endsAtMs) {
        return new TimedBuff(key, null, scope, endsAtMs);
    }
}
