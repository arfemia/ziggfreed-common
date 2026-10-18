package com.ziggfreed.common.objectives.interaction;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.objectives.gate.RequiresVolumeAudit;
import com.ziggfreed.common.objectives.gate.ZigRequiresBootstrap;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the item interaction Types that feed progression, pay rewards and gate on a {@code Requires}
 * block: {@code ZigCreditProgress}, {@code ZigGrantReward} and {@code ZigRequires}; {@code ZigRequires}' native
 * condition twins in the engine's other condition maps ({@link ZigRequiresBootstrap}); and the every-boot audit
 * of the packs' trigger-volume effect files for a {@code ZigRequires} the engine would let through
 * ({@link RequiresVolumeAudit}). Registration only.
 */
public final class ProgressInteractionsBootstrap {

    private ProgressInteractionsBootstrap() {
    }

    /** The Types as specs. Building a spec never builds its codec. */
    @Nonnull
    public static List<InteractionTypeSpec> specs() {
        return List.of(
                InteractionTypeSpec.of(ZigCreditProgressInteraction.TYPE_NAME,
                        ZigCreditProgressInteraction.class, ZigCreditProgressInteraction::getCODEC),
                InteractionTypeSpec.of(ZigGrantRewardInteraction.TYPE_NAME,
                        ZigGrantRewardInteraction.class, ZigGrantRewardInteraction::getCODEC),
                InteractionTypeSpec.of(ZigRequiresInteraction.TYPE_NAME,
                        ZigRequiresInteraction.class, ZigRequiresInteraction::codec));
    }

    /**
     * Register the Types, then the condition Types, then hang the trigger-volume audit on boot. Call from plugin
     * {@code setup()}, before any asset decode: an item naming a Type the registry has not seen fails to load,
     * and a trigger volume drops a condition it cannot decode. An interaction or condition Type that fails to
     * register throws, at setup, after its own line ({@link InteractionTypes}, {@link ZigRequiresBootstrap}); only
     * the audit is fail-soft.
     */
    public static void registerProgressInteractions(@Nonnull PluginBase plugin) {
        InteractionTypes.registerAll(plugin, specs());
        ZigRequiresBootstrap.registerConditions(plugin);
        registerRequiresAudit(plugin);
    }

    /**
     * The audit of every loaded pack's trigger-volume effect files, on each boot and never behind the boot-audit
     * switch: the engine skips a {@code ZigRequires} it cannot decode and decodes a misspelt key as a block that
     * asks for nothing, keeping the volume either way, so each such condition is named as an ERROR.
     */
    private static void registerRequiresAudit(@Nonnull PluginBase plugin) {
        try {
            plugin.getEventRegistry().register(BootEvent.class, event -> RequiresVolumeAudit.run());
        } catch (Throwable t) {
            SafeLog.warn("[gate] the trigger-volume Requires audit could not be hung on boot", t);
        }
    }
}
