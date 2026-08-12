package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * A side-channel that sees every progress event a FULL dispatch carries, whether or not any
 * objective cared about it. That is the point: a consumer counting lifetime totals needs the events
 * nothing in the catalogue was listening for too.
 *
 * <p>Called once per dispatch, BEFORE any objective is touched, and only when
 * {@link DispatchOptions#tapObservers()} is set - so the follow-up dispatches that re-fire one
 * action under a second id cannot double-count it.
 *
 * <p>Implementations must not throw and should be cheap; the engine guards the call, but a slow tap
 * slows every event in the game.
 */
@FunctionalInterface
public interface ProgressDispatchTap {

    /** Sees nothing. */
    ProgressDispatchTap NONE = (subject, kind, target, qualifier, amount, zone) -> {
    };

    /** One progress event, exactly as it was dispatched. */
    void observe(@Nonnull Subject subject, @Nonnull String kind, @Nonnull String target,
                 @Nullable String qualifier, long amount, @Nullable ZoneRef zone);
}
