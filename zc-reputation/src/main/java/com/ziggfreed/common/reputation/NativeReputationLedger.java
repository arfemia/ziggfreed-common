package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Standing kept in the engine's own value alone: each reading is the engine's number (its starting value when
 * untouched) and each write goes through the engine, whose clamp applies. What a service built with no ledger
 * uses, and what {@link TrackReputationLedger} falls back on for a player whose track cannot be read (the track
 * record never registered, or the reputation's track not provided yet), so the standing is never lost.
 */
public final class NativeReputationLedger implements ReputationLedger {

    private final ReputationNative engine;

    public NativeReputationLedger(@Nonnull ReputationNative engine) {
        this.engine = engine;
    }

    @Nullable
    @Override
    public Reading read(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
        Integer earned = engine.earned(store, ref, def.id());
        return earned == null ? null : Reading.of(earned, engine.hasEntry(store, ref, def.id()));
    }

    @Nullable
    @Override
    public Reading add(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, int delta, @Nonnull String source) {
        Integer after = engine.add(store, ref, def.id(), delta);
        return after == null ? null : Reading.of(after, true);
    }
}
