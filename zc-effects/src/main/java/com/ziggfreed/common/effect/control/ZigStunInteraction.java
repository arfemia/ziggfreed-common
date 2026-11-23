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
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;

/**
 * Stuns (or staggers) the entity a chain names, through the one landing ({@link ControlLanding}, a Stun):
 *
 * <pre>{@code
 * { "Type": "ZigStun", "Entity": "Target", "Duration": 1.5 }
 * { "Type": "ZigStun", "Entity": "User", "Duration": 8, "IgnoreImmune": true, "ImmunityRamp": 0 }
 * }</pre>
 *
 * <p>Written where vanilla's bomb writes its {@code EffectCondition} + {@code ChangeStat Immunity} +
 * {@code Interrupt} + {@code ApplyEffect Stun} run, it is that whole run in one node, plus what no asset can say: a
 * creature's current combat action ends and its attacks hold for the stun. It lands as every stun does, by the Stun
 * kind's own rule (the Control Kinds screen): the target's rules first (as an ability's own), an immune target passed
 * over, a target stunned again soon stunned less each time, and each landing adding Immunity, so repeated stuns earn
 * a window of immunity. {@code Diminish} and {@code ImmunityRamp} override the kind's shrink and ramp for this step;
 * {@code IgnoreImmune} is a fight's own stun on its boss, which no immunity refuses or shortens. On a player the hold
 * is their own game's ({@link StunControl}).
 *
 * <p>Always resolves Finished, a target that was not stunned included, so the chain carries on either way.
 */
public final class ZigStunInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigStun";

    static final double DEFAULT_DURATION = 2.0;

    @Nullable
    protected InteractionTarget entity;
    @Nullable
    protected Double duration;
    @Nullable
    protected Boolean stagger;
    @Nullable
    protected Double immunityRamp;
    @Nullable
    protected Boolean diminish;
    @Nullable
    protected Boolean ignoreImmune;
    @Nullable
    protected Boolean byTalent;

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
                        + " effect once it fills, as the game's own bombs do; 0 adds none. The Stun kind's own when"
                        + " unauthored (the Control Kinds screen).")
                .add()
                .appendInherited(new KeyedCodec<>("Diminish", Codec.BOOLEAN),
                        (i, v) -> i.diminish = v, i -> i.diminish, (i, p) -> i.diminish = p.diminish)
                .documentation("True: the stun lasts its Duration times 1 less the share of Immunity the entity"
                        + " already carries, so an entity stunned again soon is stunned less each time until it turns"
                        + " Immune. False: its full Duration. The Stun kind's own when unauthored (shorter on repeat"
                        + " as shipped).")
                .add()
                .appendInherited(new KeyedCodec<>("IgnoreImmune", Codec.BOOLEAN),
                        (i, v) -> i.ignoreImmune = v, i -> i.ignoreImmune, (i, p) -> i.ignoreImmune = p.ignoreImmune)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the stun lands at its full Duration even on an entity that is immune to stuns"
                        + " (wearing Immune, or an effect tagged ZigStunImmune) or carries Immunity, for a stun a fight"
                        + " itself deals, such as a boss crashing into a pillar while no player's stun can touch him."
                        + " False when unauthored.")
                .add()
                .appendInherited(new KeyedCodec<>("ByTalent", Codec.BOOLEAN),
                        (i, v) -> i.byTalent = v, i -> i.byTalent, (i, p) -> i.byTalent = p.byTalent)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: a talent added this stun to the move (a knack a proc runs), which a boss or an"
                        + " elite takes only where its rules let a talent's stun land (a window, a phase). False when"
                        + " unauthored: the chain's own move, which one takes unless its rules shrug a stun.")
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

    /** The Immunity a landing adds as written, or null for the Stun kind's own. */
    @Nullable
    Double immunityRamp() {
        return immunityRamp == null || !Double.isFinite(immunityRamp) ? null : Math.max(0.0, immunityRamp);
    }

    /** Does the stun shrink by the Immunity the entity already carries, as written, or null for the kind's own? */
    @Nullable
    Boolean diminishes() {
        return diminish;
    }

    /** Does the stun land on an immune entity? False unless written true. */
    boolean ignoresImmunity() {
        return Boolean.TRUE.equals(ignoreImmune);
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
            if (target == null) {
                return true;
            }
            ControlLanding.Amount seconds = ControlLanding.Amount.of(duration());
            Double ramp = immunityRamp();
            ControlLanding.Landing landing = (isStagger() ? ControlLanding.Landing.stagger(seconds)
                    : ControlLanding.Landing.stun(seconds))
                    .by(InteractionCtx.owner(ctx))
                    .talent(Boolean.TRUE.equals(byTalent))
                    .shrinks(diminishes())
                    .ramp(ramp == null ? null : ControlLanding.Amount.of(ramp));
            ControlLanding.land(buffer, target, ignoresImmunity() ? landing.ignoringImmunity() : landing);
            return true;
        });
    }
}
