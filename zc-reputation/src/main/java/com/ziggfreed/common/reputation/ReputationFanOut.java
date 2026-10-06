package com.ziggfreed.common.reputation;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Where the service's outcomes go. Production is {@code EngineReputationFanOut} (the native events, the
 * World bar, the rank notice, the Beyond payout); a test records them. Called on the world thread; a
 * failure here never undoes a write.
 */
public interface ReputationFanOut {

    /** Hears nothing. */
    ReputationFanOut NONE = new ReputationFanOut() {
        @Override
        public void changed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                @Nonnull ReputationChange change) {
        }

        @Override
        public void credit(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID playerId,
                @Nonnull ReputationDef reputation, @Nonnull List<ReputationLadder.Rank> held) {
        }

        @Override
        public void rose(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                @Nonnull ReputationDef reputation, @Nonnull ReputationLadder.Rank rank) {
        }
    };

    /** Earned standing changed: the change event, the bar, the Beyond payout. */
    void changed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull ReputationChange change);

    /**
     * A check found the player has reached {@code held} with {@code reputation} (from its starting rank to
     * the current one, or below the start only the ranks reached going down; bottom first): credit each rank.
     */
    void credit(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID playerId,
            @Nonnull ReputationDef reputation, @Nonnull List<ReputationLadder.Rank> held);

    /** The player's effective rank with {@code reputation} rose during play to {@code rank}: the notice. */
    void rose(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull ReputationDef reputation,
            @Nonnull ReputationLadder.Rank rank);
}
