package com.ziggfreed.common.world;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the library's block rules at plugin {@code setup()}. One phase, called once from
 * {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on call ORDER.
 */
public final class BlockRulesBootstrap {

    private BlockRulesBootstrap() {
    }

    /**
     * Register {@link BlockRules} as a gameplay config plugin group ({@code Plugin.ZigBlockRules}), before
     * any gameplay config decodes, and the systems that enforce it ({@link BlockRuleSystems}). The two
     * halves are guarded separately, and each warning names what a world writing the rule then lets
     * through (the engine keeps an unregistered group unread, so the config itself still loads).
     */
    public static void registerBlockRules(@Nonnull PluginBase plugin) {
        try {
            plugin.getCodecRegistry(GameplayConfig.PLUGIN_CODEC)
                    .register(BlockRules.class, BlockRules.ID, BlockRules.CODEC);
        } catch (Throwable t) {
            SafeLog.warn("[build] the ZigBlockRules gameplay config group could not be registered: a world"
                    + " whose gameplay config writes CreativeOnlyBreaking lets every breaker through this boot", t);
        }
        try {
            plugin.getEntityStoreRegistry().registerSystem(new BlockRuleSystems.AttributedDamage());
            plugin.getEntityStoreRegistry().registerSystem(new BlockRuleSystems.UnattributedDamage());
            plugin.getEntityStoreRegistry().registerSystem(new BlockRuleSystems.BlockCycle());
        } catch (Throwable t) {
            SafeLog.warn("[build] the block rules' systems could not be registered: a world with"
                    + " CreativeOnlyBreaking lets every breaker through this boot", t);
        }
    }
}
