package com.ziggfreed.common.damage.pvp;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers the PvP hit step ({@link PvpHitSystem}: a disarmed attacker's hits cancelled, a player's hits on another
 * player cut by the victim's PvP stats) and the disarm's chain refusal ({@link DisarmChainSystem}) at plugin
 * {@code setup()}, after the crit step. Registration only. Nothing to wire for a consumer: a server running the library
 * alone gets both, and content only raises the stats or tags an effect. A failed registration rethrows (R700).
 */
public final class PvpHitBootstrap {

    private PvpHitBootstrap() {
    }

    /** Call once from plugin {@code setup()}, after the on-hit steps are installed. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new PvpHitSystem.Disarmed());
            plugin.getEntityStoreRegistry().registerSystem(new PvpHitSystem.Stamp());
            plugin.getEntityStoreRegistry().registerSystem(new PvpHitSystem());
            plugin.getEntityStoreRegistry().registerSystem(new DisarmChainSystem());
        } catch (Throwable t) {
            throw new IllegalStateException("[pvp] could not register the PvP hit step and the disarm", t);
        }
    }
}
