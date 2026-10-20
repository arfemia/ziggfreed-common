package com.ziggfreed.common.health;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the regeneration pass ({@link StatRegenSystem}) that pays the {@code Regen_Health} and
 * {@code Regen_Mana} stats. Registration only. Nothing to wire for a consumer: a server running the library alone
 * regenerates whatever raises those stats, and a consumer only raises them.
 */
public final class RegenBootstrap {

    private RegenBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new StatRegenSystem());
        } catch (Throwable t) {
            SafeLog.warn("[regen] could not register the regeneration pass, so the Regen stats regain nothing "
                    + "this boot", t);
        }
    }
}
