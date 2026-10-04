package com.ziggfreed.common.effect.costume;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;

/**
 * Puts a costume on the player a chain targets (the hit of a wand's Selector, say):
 *
 * <pre>{@code
 * { "Type": "ZigCostume", "EffectId": "Yourpack_Costume_Ghost",
 *   "Next": { "Type": "ZigCreditProgress", "Qualifier": "Costume" } }
 * }</pre>
 *
 * <p>Any player can be dressed except the one dressing them, and whoever is dressed is told on the
 * spot that {@code /zigcostume off} takes it off ({@link CostumeMessages#dressed}). The costume is the
 * effect itself ({@link Costumes#dress}), so it ends when its asset says, and nothing is kept about who
 * put it on.
 *
 * <p><b>Resolves Failed whenever nobody was dressed</b>: no player hit, the dresser themselves, an
 * effect that is no costume, a wearer already in this very costume, a wearer already in another costume
 * or under a transformation they cannot take off. That is this Type's gate, chosen on purpose over the
 * toolkit's skip, so the chain's {@code Next} (a credit for the costume, say) runs only for a costume
 * that landed, never for a re-dress, and its {@code Failed} branch can answer a miss. Deciding that needs
 * the server's answer, hence {@link WaitForDataFrom#Server}.
 */
public final class ZigCostumeInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigCostume";

    @Nullable
    protected String effectId;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigCostumeInteraction> CODEC = BuilderCodec.builder(
                        ZigCostumeInteraction.class, ZigCostumeInteraction::new, SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("EffectId", Codec.STRING),
                        (i, v) -> i.effectId = v, i -> i.effectId, (i, p) -> i.effectId = p.effectId)
                .documentation("The costume: the id of an EntityEffect that changes the wearer's model "
                        + "(ModelChange) and is not a Debuff. It goes on the player this node targets, who "
                        + "can take it off with /zigcostume off.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigCostumeInteraction> getCODEC() {
        return Holder.CODEC;
    }

    /** This Type can resolve Failed, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server dresses anybody; a simulated pass would put the costume on twice. */
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
            Ref<EntityStore> target = InteractionCtx.target(ctx);
            PlayerRef wearer = InteractionCtx.player(ctx, target);
            if (buffer == null || target == null || wearer == null) {
                return false;
            }
            PlayerRef dresser = InteractionCtx.player(ctx, InteractionCtx.owner(ctx));
            if (dresser != null && CostumeRules.dressingYourself(dresser.getUuid(), wearer.getUuid())) {
                return false;
            }
            if (Costumes.dress(buffer, target, effectId) != Costumes.DressOutcome.DRESSED) {
                return false;
            }
            wearer.sendMessage(CostumeMessages.dressed(dresser == null ? null : dresser.getUsername()));
            return true;
        });
    }
}
