package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers crowd control: the four native interaction Types ({@code ZigStun}, {@code ZigTaunt},
 * {@code ZigAggroDrop}, {@code ZigAggroSuppress}), the per-world aggro windows, the attitude provider and the
 * sweep. Registration only.
 */
public final class ControlBootstrap {

    private ControlBootstrap() {
    }

    /**
     * Call from plugin {@code setup()}, before any asset decode: an asset naming a Type the registry has not seen
     * fails to load. The Types are not guarded, so a failed registration throws here, at setup (R700); the windows
     * and systems are, since without them a stun still works and only the aggro windows go quiet.
     */
    public static void install(@Nonnull PluginBase plugin) {
        registerTypes(plugin);
        try {
            AggroWindows.install(plugin.getEntityStoreRegistry().registerResource(AggroWindows.class,
                    AggroWindows::new));
            plugin.getEntityStoreRegistry().registerSystem(new AggroAttitudeSystem());
            plugin.getEntityStoreRegistry().registerSystem(new AggroSweepSystem());
        } catch (Throwable t) {
            SafeLog.warn("[control] the aggro windows could not be registered: taunts and aggro drops do nothing"
                    + " this boot", t);
        }
    }

    private static void registerTypes(@Nonnull PluginBase plugin) {
        plugin.getCodecRegistry(Interaction.CODEC).register(ZigStunInteraction.TYPE_NAME, ZigStunInteraction.class,
                ZigStunInteraction.getCODEC());
        plugin.getCodecRegistry(Interaction.CODEC).register(ZigTauntInteraction.TYPE_NAME, ZigTauntInteraction.class,
                ZigTauntInteraction.getCODEC());
        plugin.getCodecRegistry(Interaction.CODEC).register(ZigAggroDropInteraction.TYPE_NAME,
                ZigAggroDropInteraction.class, ZigAggroDropInteraction.getCODEC());
        plugin.getCodecRegistry(Interaction.CODEC).register(ZigAggroSuppressInteraction.TYPE_NAME,
                ZigAggroSuppressInteraction.class, ZigAggroSuppressInteraction.getCODEC());
    }
}
