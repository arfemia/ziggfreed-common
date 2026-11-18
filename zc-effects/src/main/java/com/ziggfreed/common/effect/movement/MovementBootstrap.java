package com.ziggfreed.common.effect.movement;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.effect.sync.StatEffectSync;

/**
 * Registers the movement pass ({@link MovementReconciler}): its channel on the one stat-to-effect pass, which keeps
 * every living carrier's speed and knockback effects in step with its {@code MoveSpeed} and {@code KnockbackResist}
 * stats; and the thrown projectile's speed ({@link ThrowSpeeds}), which a thrower's {@code ThrowSpeed} stat raises.
 * Registration only. Nothing to wire for a consumer: a server running the library alone gets the stats' speed,
 * knockback and throw, and a consumer only raises the stats.
 */
public final class MovementBootstrap {

    private MovementBootstrap() {
    }

    /** Call once from plugin {@code setup()}, after the stat-to-effect pass is registered. */
    public static void install(@Nonnull PluginBase plugin) {
        StatEffectSync.register(MovementReconciler.CHANNEL);
        plugin.getEntityStoreRegistry().registerSystem(new ThrowSpeeds.Launched());
        plugin.getEntityStoreRegistry().registerSystem(new ThrowSpeeds.Speeding());
    }
}
