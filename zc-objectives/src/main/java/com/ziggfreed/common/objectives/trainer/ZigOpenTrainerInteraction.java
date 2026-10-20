package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Opens a trainer's page from any native interaction chain (R700, the command-interactions paradigm): an item's, a
 * block's, or one aimed at an NPC.
 *
 * <pre>{@code
 * { "Type": "ZigOpenTrainer", "Placement": "Zc_Trainer_Example" }   a named trainer, from a block or an item
 * { "Type": "ZigOpenTrainer" }                                      the placed NPC the chain is aimed at
 * }</pre>
 *
 * <p>Unnamed, the trainer is the placement that stood the entity the chain targets. It resolves Failed when no
 * trainer answers (the player is told so) and Finished once the page is open, so a chain's {@code Next} runs only for
 * a page that opened; deciding that needs the server, hence {@link WaitForDataFrom#Server}.
 */
public final class ZigOpenTrainerInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigOpenTrainer";

    @Nullable protected String placement;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigOpenTrainerInteraction> CODEC = BuilderCodec.builder(
                        ZigOpenTrainerInteraction.class, ZigOpenTrainerInteraction::new,
                        SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Placement", Codec.STRING, false),
                        (i, v) -> i.placement = v, i -> i.placement, (i, p) -> i.placement = p.placement)
                .documentation("The trainer to open, by its NPC placement id. Leave it out on a chain aimed at an "
                        + "NPC: the trainer is the placement that stood it. A placement that is no trainer here "
                        + "opens nothing, and the chain resolves Failed.").add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigOpenTrainerInteraction> getCODEC() {
        return Holder.CODEC;
    }

    /** This Type can resolve Failed, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server opens a page. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
            Ref<EntityStore> user = InteractionCtx.owner(ctx);
            if (buffer == null || user == null) {
                return false;
            }
            Player player = buffer.getComponent(user, Player.getComponentType());
            if (player == null) {
                return false;
            }
            Ref<EntityStore> ref = player.getReference();
            Store<EntityStore> store = ref.getStore();
            Ref<EntityStore> target = InteractionCtx.target(ctx);
            String aimedAt = placementOf(buffer, target);
            String trainer = Trainers.resolve(placement, aimedAt, null);
            // The aimed NPC anchors the page (and its reach) only when it is the resolved trainer's own: a chain naming
            // trainer X while aimed at NPC Y anchors on the player, so reach reads X's own spots.
            Ref<EntityStore> anchor = target != null && aimedAt != null && trainer != null
                    && trainer.equalsIgnoreCase(aimedAt.trim()) ? target : ref;
            return TrainerPages.open(trainer, store, anchor, player);
        });
    }

    /** The placement id stamped on {@code entity}, or null when it carries none. */
    @Nullable
    private static String placementOf(@Nonnull CommandBuffer<EntityStore> buffer, @Nullable Ref<EntityStore> entity) {
        if (entity == null) {
            return null;
        }
        try {
            var type = PlacedNpcComponent.getComponentType();
            PlacedNpcComponent stamp = type == null ? null : buffer.getComponent(entity, type);
            return stamp == null || stamp.placementId == null || stamp.placementId.isBlank() ? null
                    : stamp.placementId;
        } catch (Throwable t) {
            SafeLog.fine("[trainer] could not read the placement stamp: " + t.getMessage());
            return null;
        }
    }
}
