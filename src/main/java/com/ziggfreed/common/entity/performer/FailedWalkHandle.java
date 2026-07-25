package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The immutable {@link WalkHandle} a backend returns when a walk could not even start (no path,
 * dead ref, missing world). Always {@link State#FAILED}; {@link #poll}/{@link #cancel} are no-ops.
 * A singleton, since it carries no state.
 */
final class FailedWalkHandle implements WalkHandle {

    static final FailedWalkHandle INSTANCE = new FailedWalkHandle();

    private FailedWalkHandle() {
    }

    @Override
    @Nonnull
    public State poll(@Nonnull ComponentAccessor<EntityStore> accessor, double dtMs) {
        return State.FAILED;
    }

    @Override
    @Nonnull
    public State state() {
        return State.FAILED;
    }

    @Override
    public void cancel() {
        // nothing to cancel.
    }
}
