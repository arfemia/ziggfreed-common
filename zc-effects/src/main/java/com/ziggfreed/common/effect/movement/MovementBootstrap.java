package com.ziggfreed.common.effect.movement;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the movement pass ({@link MovementReconciler}): the per-tick system that keeps every living player's
 * speed and knockback effects in step with their {@code MoveSpeed} and {@code KnockbackResist} stats. Registration
 * only. Nothing to wire for a consumer: a server running the library alone gets the stats' speed and knockback, and a
 * consumer only raises the stats.
 */
public final class MovementBootstrap {

    private MovementBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new MovementSyncSystem());
        } catch (Throwable t) {
            SafeLog.warn("[movement] could not register the movement pass, so MoveSpeed and KnockbackResist do "
                    + "nothing this boot", t);
        }
    }
}
