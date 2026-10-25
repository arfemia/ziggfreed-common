package com.ziggfreed.common.effect.duration;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

/**
 * Registers how long an effect lasts as it lands, by stats ({@link EffectDurations}): the player pass, the hit-target
 * pass, the watch a player's hit puts on its target and the forgetting of an entity that left. Registration only.
 * Nothing to wire for a consumer: the rules are effect families' {@code Duration} groups, data any pack writes. A
 * failed registration rethrows (R700): every effect-length stat would be gone.
 */
public final class DurationBootstrap {

    private DurationBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(new EffectDurationSystems.Carriers());
            plugin.getEntityStoreRegistry().registerSystem(new EffectDurationSystems.Watched());
            plugin.getEntityStoreRegistry().registerSystem(new EffectDurationSystems.Watch());
            plugin.getEntityStoreRegistry().registerSystem(new EffectDurationSystems.Left());
        } catch (Throwable t) {
            throw new IllegalStateException("[duration] could not register the effect length pass", t);
        }
    }
}
