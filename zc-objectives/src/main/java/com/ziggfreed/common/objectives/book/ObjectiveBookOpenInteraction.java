package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;

/**
 * The interaction an item names to open the {@link ObjectiveBookPage}. Authored as
 * {@code {"Type": "ZigOpenObjectiveBook"}} inside any native interaction chain; the shipped
 * {@code Ziggfreed_Objective_Book} item chains it off a short Charging hold.
 *
 * <p>Takes no authored parameter: one book, one page. A parameterised variant (a book that opens
 * straight to a chosen tab, say) would add its own codec leaf and be authored in object form,
 * the way any per-item-parameterised Type is.
 */
public final class ObjectiveBookOpenInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigOpenObjectiveBook";

    /**
     * LAZY codec holder. An eager {@code public static final CODEC} field would force
     * {@code Interaction.ABSTRACT_CODEC}'s class-init (and through it {@code RangeValidator} ->
     * {@code HytaleLogger}) the moment this class loads, which throws outside a live server;
     * {@code InteractionTypeSpec} holds the codec behind a supplier for exactly this reason.
     */
    private static final class Holder {
        static final BuilderCodec<ObjectiveBookOpenInteraction> CODEC = BuilderCodec.builder(
                ObjectiveBookOpenInteraction.class, ObjectiveBookOpenInteraction::new,
                SimpleInstantInteraction.CODEC).build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ObjectiveBookOpenInteraction> getCODEC() {
        return Holder.CODEC;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
                            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
            Ref<EntityStore> entity = InteractionCtx.firingEntity(ctx);
            if (buffer == null || entity == null) {
                return false;
            }
            Player player = buffer.getComponent(entity, Player.getComponentType());
            if (player == null) {
                return false;
            }
            Ref<EntityStore> ref = player.getReference();
            Store<EntityStore> store = ref.getStore();
            // The PlayerRef COMPONENT, read off the store; the engine's own player-side accessor
            // for it is deprecated for removal.
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return false;
            }
            player.getPageManager().openCustomPage(ref, store, new ObjectiveBookPage(playerRef));
            return true;
        });
    }
}
