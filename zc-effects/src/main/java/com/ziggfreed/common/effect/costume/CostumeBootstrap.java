package com.ziggfreed.common.effect.costume;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.util.SafeLog;

/** Registers the costume: the {@code ZigCostume} Type and the {@code /zigcostume} family. Registration only. */
public final class CostumeBootstrap {

    private CostumeBootstrap() {
    }

    /** The Type as a spec. Building it never builds the codec. */
    @Nonnull
    static InteractionTypeSpec spec() {
        return InteractionTypeSpec.of(ZigCostumeInteraction.TYPE_NAME, ZigCostumeInteraction.class,
                ZigCostumeInteraction::getCODEC);
    }

    /**
     * Register the Type and the command. Call from plugin {@code setup()}, before any asset decode: an
     * item naming a Type the registry has not seen fails to load. A Type that fails to register throws,
     * at setup, after its own line ({@link InteractionTypes}); the command is fail-soft.
     */
    public static void registerCostumes(@Nonnull PluginBase plugin) {
        InteractionTypes.register(plugin, spec());
        try {
            plugin.getCommandRegistry().registerCommand(new CostumeCommand());
        } catch (Throwable t) {
            SafeLog.warn("[costume] the /zigcostume command could not be registered", t);
        }
    }
}
