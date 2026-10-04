package com.ziggfreed.common.objectives.interaction;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the item interaction Types that feed progression and pay rewards: {@code ZigCreditProgress}
 * and {@code ZigGrantReward}. Registration only.
 */
public final class ProgressInteractionsBootstrap {

    private ProgressInteractionsBootstrap() {
    }

    /** Both Types as specs. Building a spec never builds its codec. */
    @Nonnull
    public static List<InteractionTypeSpec> specs() {
        return List.of(
                InteractionTypeSpec.of(ZigCreditProgressInteraction.TYPE_NAME,
                        ZigCreditProgressInteraction.class, ZigCreditProgressInteraction::getCODEC),
                InteractionTypeSpec.of(ZigGrantRewardInteraction.TYPE_NAME,
                        ZigGrantRewardInteraction.class, ZigGrantRewardInteraction::getCODEC));
    }

    /**
     * Register both Types. Call from plugin {@code setup()}, before any asset decode: an item naming a
     * Type the registry has not seen fails to load. Fail-soft per Type.
     */
    public static void registerProgressInteractions(@Nonnull PluginBase plugin) {
        try {
            InteractionTypes.registerAll(plugin, specs());
        } catch (Throwable t) {
            SafeLog.warn("[interaction] the progress interaction Types could not be registered", t);
        }
    }
}
