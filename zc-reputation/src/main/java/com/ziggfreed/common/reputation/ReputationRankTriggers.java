package com.ziggfreed.common.reputation;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.EquipStatBridge;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where the effective-rank checks come from besides a change: once at login (a silent hydrate), after
 * every equip recompute (a listener on the library's one installed equip bridge, never a second bridge or
 * trigger), and the transient memory dropped at disconnect. Each check runs on a later world task: at
 * login after the player is in, and after an equip so the engine's own armor stat fold (a scheduled
 * recalculation) has had its turn before the gear share is read.
 */
public final class ReputationRankTriggers {

    /** The one equip listener; one instance, so the bridge's identity dedupe keeps it single. */
    static final EquipStatBridge.AppliedListener ON_EQUIP = ReputationRankTriggers::onEquipApplied;

    private static final AtomicBoolean WARNED_NO_BRIDGE = new AtomicBoolean();

    private ReputationRankTriggers() {
    }

    /** Login: check every reputation once the player is in, as a hydrate. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> check(ref, ReputationRankWatch.Trigger.LOGIN));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the login rank check could not be queued: " + t.getMessage());
        }
    }

    /** Disconnect: forget the player's last-seen ranks. */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            UUID uuid = playerRef == null ? null : playerRef.getUuid();
            if (uuid != null) {
                ReputationRuntime.service().forget(uuid);
            }
        } catch (Throwable t) {
            SafeLog.warn("[reputation] forgetting a leaving player's ranks failed: " + t.getMessage());
        }
    }

    /** Hang the equip check on {@code bridge}; false (said once) when no bridge is installed. */
    public static boolean hangOnBridge(@Nullable EquipStatBridge bridge) {
        if (bridge == null) {
            if (WARNED_NO_BRIDGE.compareAndSet(false, true)) {
                SafeLog.warn("[reputation] no equip bridge is installed, so gear moves a reputation's rank only "
                        + "at the player's next change or login");
            }
            return false;
        }
        bridge.addAppliedListener(ON_EQUIP);
        return true;
    }

    /** After a recompute: a player only, and only while the module is on; the check itself runs later. */
    static void onEquipApplied(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            if (!ReputationRuntime.service().isOn() || store.getComponent(ref, Player.getComponentType()) == null) {
                return;
            }
            store.getExternalData().getWorld().execute(() -> check(ref, ReputationRankWatch.Trigger.EQUIP));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the equip rank check could not be queued: " + t.getMessage());
        }
    }

    private static void check(@Nonnull Ref<EntityStore> ref, @Nonnull ReputationRankWatch.Trigger trigger) {
        if (ref.isValid()) {
            ReputationRuntime.service().checkAll(ref.getStore(), ref, trigger);
        }
    }
}
