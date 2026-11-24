package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the on-hit steps at plugin {@code setup()}: the crit step ({@link CritSystem}, after the weak-spot step),
 * the true share step ({@link DamageDealtSystem}, after the crit step) and the lifesteal step
 * ({@link LifestealSystem}). Registration only. Nothing to wire for a consumer: a server running
 * the library alone gets each for any attacker raising {@code CritChance}, {@code Lifesteal} or lowering
 * {@code DamageDealt}, and a consumer only
 * raises the stats, registers a source of its own ({@link Crits#registerChanceSource}, {@link Lifesteal#registerSource})
 * or installs its one rule ({@link Crits#installHitRule}, {@link Lifesteal#installHitRule}). A failed registration
 * rethrows (R700): crits and lifesteal would be gone.
 */
public final class OnHitBootstrap {

    private OnHitBootstrap() {
    }

    /** Call once from plugin {@code setup()}, after the weak-spot step is installed. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new CritSystem());
            plugin.getEntityStoreRegistry().registerSystem(new DamageDealtSystem());
            plugin.getEntityStoreRegistry().registerSystem(new LifestealSystem());
        } catch (Throwable t) {
            throw new IllegalStateException("[onhit] could not register the crit, true share and lifesteal steps", t);
        }
    }
}
