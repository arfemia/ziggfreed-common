package com.ziggfreed.common.pvp;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.owner.OwnerFileBindings;

/**
 * Wires the PvP ledger at plugin {@code setup()} ({@link PvpLedger}), called once from {@code ZiggfreedCommonPlugin.setup()},
 * before any gameplay config or asset decodes: the {@code Plugin.ZigPvp} gameplay config group ({@link PvpRules}); the
 * per-player record and its connect hook ({@link PvpRecord}); the hit tally and the death and return
 * ({@link PvpSystems}); a departure mid-fight and a world change; the {@code ZigGrudge} condition; the
 * {@code ziggfreedcommon:pvp_streak} and {@code pvp_bounty} readings; the record's numbers as native statistics; the
 * PvP Rules screen's owner-file binding; and a gameplay config load moving every world's rules. The rule store is the
 * root's, with every framework store. Registration only. A failed registration rethrows (R700): the ledger would be
 * half there.
 */
public final class PvpBootstrap {

    private PvpBootstrap() {
    }

    /** Call once from plugin {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            plugin.getCodecRegistry(GameplayConfig.PLUGIN_CODEC).register(PvpRules.class, PvpRules.ID, PvpRules.CODEC);
            plugin.getCodecRegistry(Condition.CODEC).register(ZigGrudgeCondition.TYPE_NAME, ZigGrudgeCondition.class,
                    ZigGrudgeCondition.codec());
            PvpRecord.register(plugin.getEntityStoreRegistry());
            PvpRecord.install(plugin);
            plugin.getEntityStoreRegistry().registerSystem(new PvpSystems.Struck());
            plugin.getEntityStoreRegistry().registerSystem(new PvpSystems.Died());
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, PvpLedger::onDisconnect);
            plugin.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, PvpLedger::onLeftWorld);
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, GameplayConfig.class,
                    (LoadedAssetsEvent<String, GameplayConfig, AssetMap<String, GameplayConfig>> event) ->
                            PvpWorldRules.invalidate());
            PvpFactors.contribute();
            PvpStatReaders.register();
            OwnerFileBindings.register(PvpRuleForm.binding());
        } catch (Throwable t) {
            throw new IllegalStateException("[pvp] could not wire the PvP ledger", t);
        }
    }
}
