package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionTarget;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;

/**
 * Stuns (or staggers) the entity a chain names, through {@link StunControl}:
 *
 * <pre>{@code
 * { "Type": "ZigStun", "Entity": "Target", "Duration": 1.5 }
 * }</pre>
 *
 * <p>Written where vanilla's bomb writes its {@code EffectCondition} + {@code ChangeStat Immunity} +
 * {@code Interrupt} + {@code ApplyEffect Stun} run, it is that whole run in one node, plus what no asset can say: a
 * creature's current combat action ends and its attacks hold for the stun. An {@code Immune} target is passed over;
 * a landed stun adds {@code ImmunityRamp} to the target's {@code Immunity}, so repeated stuns earn a window of
 * immunity. On a player the hold is their own game's ({@link StunControl}).
 *
 * <p>Always resolves Finished, a target that was not stunned included, so the chain carries on either way.
 */
public final class ZigStunInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigStun";

    static final double DEFAULT_DURATION = 2.0;
    static final double DEFAULT_IMMUNITY_RAMP = 25.0;

    @Nullable
    protected InteractionTarget entity;
    @Nullable
    protected Double duration;
    @Nullable
    protected Boolean stagger;
    @Nullable
    protected Double immunityRamp;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigStunInteraction> CODEC = BuilderCodec.builder(
                        ZigStunInteraction.class, ZigStunInteraction::new, SimpleInstantInteraction.CODEC)
                .documentation("Stuns an entity: it cannot move or attack for the duration, and what it was doing"
                        + " stops. A chain whose root is tagged Uninterruptable carries on. An entity wearing Immune is"
                        + " passed over. On a player the stun is applied by their own game.")
                .appendInherited(new KeyedCodec<>("Entity", InteractionTarget.CODEC),
                        (i, v) -> i.entity = v, i -> i.entity, (i, p) -> i.entity = p.entity)
                .documentation("Who is stunned: Target (the default, the entity this chain hit or aims at), User or"
                        + " Owner.")
                .add()
                .appendInherited(new KeyedCodec<>("Duration", Codec.DOUBLE),
                        (i, v) -> i.duration = v, i -> i.duration, (i, p) -> i.duration = p.duration)
                .documentation("How long the stun lasts, in seconds. 2 when unauthored.")
                .add()
                .appendInherited(new KeyedCodec<>("Stagger", Codec.BOOLEAN),
                        (i, v) -> i.stagger = v, i -> i.stagger, (i, p) -> i.stagger = p.stagger)
                .documentation("True for a stagger: a stun with a shorter recovery that never cuts a stun already on"
                        + " the entity short, and over a stagger only makes it longer. False when unauthored.")
                .add()
                .appendInherited(new KeyedCodec<>("ImmunityRamp", Codec.DOUBLE),
                        (i, v) -> i.immunityRamp = v, i -> i.immunityRamp, (i, p) -> i.immunityRamp = p.immunityRamp)
                .documentation("How much a landed stun adds to the entity's Immunity stat, which gives it the Immune"
                        + " effect once it fills, as the game's own bombs do. 25 when unauthored; 0 adds none.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and the codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigStunInteraction> getCODEC() {
        return Holder.CODEC;
    }

    @Nonnull
    InteractionTarget entity() {
        return entity != null ? entity : InteractionTarget.TARGET;
    }

    double duration() {
        return ControlTargets.orDefault(duration, DEFAULT_DURATION);
    }

    boolean isStagger() {
        return Boolean.TRUE.equals(stagger);
    }

    double immunityRamp() {
        return ControlTargets.orDefault(immunityRamp, DEFAULT_IMMUNITY_RAMP);
    }

    /** Only the server stuns; a simulated pass would stun twice. */
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
            if (buffer == null) {
                return false;
            }
            Ref<EntityStore> target = ControlTargets.resolve(ctx, entity, InteractionTarget.TARGET);
            if (target == null || StunControl.isImmune(buffer, target)) {
                return true;
            }
            float seconds = (float) duration();
            Ref<EntityStore> source = InteractionCtx.owner(ctx);
            boolean landed = isStagger()
                    ? StunControl.stagger(buffer, target, seconds, source)
                    : StunControl.stun(buffer, target, seconds, source);
            if (landed) {
                StunControl.rampImmunity(buffer, target, (float) immunityRamp());
            }
            return true;
        });
    }
}
