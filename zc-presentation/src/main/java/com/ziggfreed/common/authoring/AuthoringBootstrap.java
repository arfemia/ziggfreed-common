package com.ziggfreed.common.authoring;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the authoring kit at the library's {@code setup()}: the {@code ZigAuthoringTool} interaction Type every
 * authoring tool runs (before any item decodes, rethrowing a failed registration), the Inspect Tool and its wizard,
 * the {@code /zigauthor} family, and the drop of a departing player's parked wizard. Its items, root interaction and
 * creative tab are assets this module ships. A server running the library alone has all of it; a consumer registers
 * only its own tools and wizards ({@link AuthoringTools#wizardTool}, {@link AuthoringWizards#register}).
 */
public final class AuthoringBootstrap {

    private AuthoringBootstrap() {
    }

    /** At the library's {@code setup()}. */
    public static void install(@Nonnull PluginBase plugin) {
        InteractionTypes.register(plugin, InteractionTypeSpec.of(ZigAuthoringToolInteraction.TYPE_NAME,
                ZigAuthoringToolInteraction.class, ZigAuthoringToolInteraction::codec));
        AuthoringTools.wizardTool(InspectWizard.ID, InspectWizard::new);
        plugin.getCommandRegistry().registerCommand(new ZigAuthorCommand());
        try {
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, PendingPicks::onDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] the leave-time drop of a parked wizard could not be registered: a departed "
                    + "player's wizard stays parked until its window passes", t);
        }
    }
}
