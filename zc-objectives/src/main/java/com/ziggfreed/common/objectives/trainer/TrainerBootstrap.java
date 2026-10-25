package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.track.pick.PickTrainers;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the trainer at library setup, called once from the wiring root's {@code setup()} before any asset
 * decodes: the page's three parts on the page kit (a trainer block's {@code Sections} read against them), the
 * library's own three services (the prestige, choosing again what a prestige put aside, the suggested build), the
 * {@code Trainer} destination, the native {@code ZigOpenTrainer} interaction Type (a failed registration throws
 * here, R700), zc-track's {@code PickTrainers} seam filled with the placement store's answer, so a pick whose
 * trainers are all switched off is learned on its track's page, and the trainers' audit at the first player ready
 * (zc's boot audit takes that run instead when asked). Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class TrainerBootstrap {

    private TrainerBootstrap() {
    }

    /** Everything the trainer registers, in order. */
    public static void install(@Nonnull PluginBase plugin) {
        TrainerParts.registerBuiltIns();
        TrainerServices.registerBuiltIns();
        TrainerDestinations.register();
        InteractionTypes.register(plugin, InteractionTypeSpec.of(ZigOpenTrainerInteraction.TYPE_NAME,
                ZigOpenTrainerInteraction.class, ZigOpenTrainerInteraction::getCODEC));
        PickTrainers.install(Trainers::teaches);
        try {
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> TrainerAudit.runLateAudit());
        } catch (Throwable t) {
            SafeLog.warn("[trainer] the trainers' audit could not be registered", t);
        }
    }
}
