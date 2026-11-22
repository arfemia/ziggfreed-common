package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers crowd control: the four native interaction Types ({@code ZigStun}, {@code ZigTaunt},
 * {@code ZigAggroDrop}, {@code ZigAggroSuppress}), the per-world aggro windows, the attitude provider, the
 * sweep, the shedding of the game's own stuns from a stun-immune entity ({@link StunImmunitySystem}), and the emptying
 * of a knockback on a creature whose rules shrug a push ({@link ShruggedKnockbackSystem}). Registration only.
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
        try {
            plugin.getEntityStoreRegistry().registerSystem(new StunImmunitySystem());
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, EntityEffect.class,
                    (LoadedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            StunImmunity.markDirty());
            plugin.getEventRegistry().register(RemovedAssetsEvent.class, EntityEffect.class,
                    (RemovedAssetsEvent<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> ev) ->
                            StunImmunity.markDirty());
        } catch (Throwable t) {
            SafeLog.warn("[control] the stun shedding could not be registered: a stun-immune entity still takes the"
                    + " game's own stuns this boot", t);
        }
        try {
            plugin.getEntityStoreRegistry().registerSystem(new ShruggedKnockbackSystem());
        } catch (Throwable t) {
            SafeLog.warn("[control] the knockback guard could not be registered: a boss whose rules shrug a push is"
                    + " still moved by the game's own knockback this boot", t);
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
