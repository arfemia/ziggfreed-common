package com.ziggfreed.common.track;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.source.SourceBatch;
import com.ziggfreed.common.track.stance.Stances;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's settle once they are ready in a world (a login and every world change): on a later world task, so
 * the player stands in the store, every usable track's level is written onto its mirror stat, every unlock
 * Claim owed and not yet parked is parked ({@link TrackService#settle}), every rank saved past a pick's limit is given
 * back with its points ({@link PickService#holdToLimits}), every face held in a stance that stopped counting is freed
 * ({@code Stances.settle}), and the grants of their picks are rebuilt
 * under the rules of the world they now stand in ({@link TrackDerived#rebuild}): an arena that strips passive stats
 * takes them off here, and the next world gives them back.
 */
final class TrackLogin {

    private TrackLogin() {
    }

    /** Queue the settle on the player's world thread. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> settle(ref));
        } catch (Throwable t) {
            SafeLog.warn("[track] the login settle could not be queued: " + t.getMessage());
        }
    }

    private static void settle(@Nonnull Ref<EntityStore> ref) {
        UUID uuid = null;
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                uuid = player.getUuid();
                TrackService.settle(uuid);
            }
        } catch (Throwable t) {
            SafeLog.warn("[track] the login settle failed: " + t.getMessage());
        }
        // What a frequent source summed in the world they just left, paid where they now stand. Its own guard, so a
        // failed settle still pays them and a failed payment never undoes the settle.
        if (uuid != null) {
            try {
                SourceBatch.payCarried(uuid);
            } catch (Throwable t) {
                SafeLog.warn("[track] the sums carried from the last world could not be paid: " + t.getMessage());
            }
        }
        // A rank saved past a pick's limit (its file now allows fewer) given back, with its points, before the
        // grants are rebuilt. Its own guard, so a failure here never stops the rebuild.
        if (uuid != null) {
            try {
                PickService.holdToLimits(uuid);
            } catch (Throwable t) {
                SafeLog.warn("[track] the picks could not be held to their rank limits: " + t.getMessage());
            }
            // A face held in a stance that stopped counting (unowned, its slot closed, its kind past its cap) freed.
            try {
                Stances.settle(uuid);
            } catch (Throwable t) {
                SafeLog.warn("[track] the stances could not be settled: " + t.getMessage());
            }
        }
        // Its own guard: a failed settle still leaves the grants matching the world.
        TrackDerived.rebuild(ref.getStore(), ref);
    }
}
