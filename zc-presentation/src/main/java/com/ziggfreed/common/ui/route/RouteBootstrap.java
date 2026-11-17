package com.ziggfreed.common.ui.route;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;

/**
 * Wires the destination vocabulary into the engine's own interactions at the library's {@code setup()}: the
 * {@code ZigOpen} interaction Type ({@link ZigOpenInteraction}), registered before any asset decodes and rethrowing a
 * failed registration, so a block, a pad or an item can open any destination a mod registered. Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class RouteBootstrap {

    private RouteBootstrap() {
    }

    /** At the library's {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        InteractionTypes.register(plugin, InteractionTypeSpec.of(ZigOpenInteraction.TYPE_NAME,
                ZigOpenInteraction.class, ZigOpenInteraction::codec));
    }
}
