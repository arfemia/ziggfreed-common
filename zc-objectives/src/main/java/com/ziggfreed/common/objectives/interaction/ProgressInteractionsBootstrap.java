package com.ziggfreed.common.objectives.interaction;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.objectives.gate.RequiresVolumeAudit;
import com.ziggfreed.common.objectives.gate.ZigRequiresTriggerCondition;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the item interaction Types that feed progression, pay rewards and gate on a {@code Requires}
 * block: {@code ZigCreditProgress}, {@code ZigGrantReward} and {@code ZigRequires}; {@code ZigRequires}'
 * trigger-volume twin on the TriggerVolumes plugin; and the every-boot audit of the packs' trigger-volume
 * effect files for a {@code ZigRequires} the engine would let through ({@link RequiresVolumeAudit}).
 * Registration only.
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
     * Register the Types, then the trigger condition, then hang its audit on boot. Call from plugin
     * {@code setup()}, before any asset decode: an item naming a Type the registry has not seen fails to load,
     * and a trigger volume drops a condition it cannot decode. Fail-soft per step.
     */
    public static void registerProgressInteractions(@Nonnull PluginBase plugin) {
        try {
            InteractionTypes.registerAll(plugin, specs());
        } catch (Throwable t) {
            SafeLog.warn("[interaction] the progress interaction Types could not be registered", t);
        }
        registerRequiresCondition();
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

    /**
     * {@code ZigRequires} as a trigger-volume condition. The TriggerVolumes plugin sets up before the library
     * (the manifest depends on it), and it throws on a type id already taken, so a taken id is logged and the
     * first registration stays. A volume drops a condition it cannot decode and then acts for everyone, so a
     * failure here is logged as severe.
     */
    private static void registerRequiresCondition() {
        TriggerVolumesPlugin volumes = TriggerVolumesPlugin.get();
        if (volumes == null) {
            SafeLog.severe("[gate] the TriggerVolumes plugin is not running, so a trigger volume cannot gate on "
                    + ZigRequiresTriggerCondition.TYPE_NAME);
            return;
        }
        try {
            volumes.registerConditionType(ZigRequiresTriggerCondition.TYPE_NAME, ZigRequiresTriggerCondition.class,
                    ZigRequiresTriggerCondition.codec());
        } catch (IllegalArgumentException taken) {
            SafeLog.warn("[gate] the trigger condition type " + ZigRequiresTriggerCondition.TYPE_NAME
                    + " is already registered, so the first registration stays: " + taken.getMessage());
        } catch (Throwable t) {
            SafeLog.severe("[gate] the " + ZigRequiresTriggerCondition.TYPE_NAME + " trigger condition could not"
                    + " be registered, so a trigger volume gated on it acts for everyone", t);
        }
    }
}
