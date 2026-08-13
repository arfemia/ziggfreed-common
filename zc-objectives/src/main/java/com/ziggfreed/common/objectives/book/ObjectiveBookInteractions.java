package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;

import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;

/**
 * Registers the Objective Book's interaction Type.
 *
 * <p><b>Call from plugin {@code setup()}, before any asset decode</b>: an item or
 * {@code RootInteraction} naming a Type the registry has not seen yet simply fails to parse, and
 * the book item is decoded at boot. Registration is UNCONDITIONAL - whether the library's own
 * progression runtime ends up running is only settled at the first player-ready, long after the
 * item has decoded, and the page has a localized answer for the case where it does not.
 */
public final class ObjectiveBookInteractions {

    private ObjectiveBookInteractions() {
    }

    /** Register {@code ZigOpenObjectiveBook}. Fail-soft: a throwing registration logs and returns false. */
    public static boolean register(@Nonnull PluginBase plugin) {
        return InteractionTypes.register(plugin, InteractionTypeSpec.of(
                ObjectiveBookOpenInteraction.TYPE_NAME,
                ObjectiveBookOpenInteraction.class,
                ObjectiveBookOpenInteraction::getCODEC));
    }
}
