package com.ziggfreed.common.instance.play;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Optional consumer hook that lets the {@link PlayModePage} chooser surface a "Claim Rewards"
 * button for spoils a player missed (closed the results screen without claiming, or a full
 * inventory at claim time). The page shows the button only when {@link #hasPending} is true and
 * runs {@link #claim} when pressed; the consumer owns the actual reward delivery (the shared
 * {@code PendingRewardStore} drain + grant). Supplied through {@link PlayModePageDeps} - a
 * {@code null} hook means no claim button (the page stays exactly as before).
 */
public interface PlayRewardClaim {

    /** Whether {@code uuid} has any unclaimed pending rewards (drives the button's visibility). */
    boolean hasPending(@Nonnull UUID uuid);

    /**
     * Claim (grant) the player's pending rewards now, applying the full-inventory guard.
     * Returns {@code true} when everything was delivered (the button then disappears on re-render),
     * {@code false} when some reward is still held (a full inventory).
     */
    boolean claim(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
}
