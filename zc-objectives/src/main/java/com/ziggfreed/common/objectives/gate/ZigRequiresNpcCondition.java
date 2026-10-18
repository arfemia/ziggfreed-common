package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.decisionmaker.core.EvaluationContext;
import com.hypixel.hytale.server.npc.decisionmaker.core.conditions.base.SimpleCondition;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * An NPC decision condition over a {@code Requires} block: an option (a state the NPC may switch to, a
 * combat action it may take) scores its {@code TrueValue} only while the entity it is weighed against meets
 * the block, and its {@code FalseValue} (0 by default, which takes the option out) otherwise.
 *
 * <pre>{@code
 * { "Type": "ZigRequires",
 *   "Requires": { "Quests": ["Yourpack_Intro"] },
 *   "TargetSlot": "LockedTarget" }
 * }</pre>
 *
 * <p>Who it asks about: the entity the option is scored against (a combat action's target), or, when
 * {@code TargetSlot} names one of the NPC's marked-target slots, the entity locked in it, which is how a
 * state choice asks about the player the NPC is dealing with (a state option is scored against the NPC
 * itself). A player is read as the runtime's own subject; any other entity through its own factors, so a
 * {@code Quests} leaf refuses an NPC. An empty slot, or nothing to score against, refuses a block that asks
 * for something, and scores the lower of {@code TrueValue} and {@code FalseValue}, so an inverted condition
 * never takes it as a pass ({@link #calculateUtility}).
 *
 * <p>Holds no per-NPC state: the engine shares one condition across every NPC that names it. It reads the
 * store, so off the world thread (an engine that scores in parallel) it refuses rather than reads
 * ({@link RequiresCheck#query}). Its simplicity is the high-cost one, so the engine weighs it after the
 * cheaper conditions of the same option and skips it once one of them has taken the option out.
 *
 * <p>Registered into the engine's NPC decision condition map at setup ({@link ZigRequiresBootstrap}); a file
 * under {@code Server/NPC/DecisionMaking/Conditions/} or an inline condition can name it.
 */
public final class ZigRequiresNpcCondition extends SimpleCondition {

    /** The authored {@code "Type"} name, the same word every {@code ZigRequires} uses. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    private GateSpec requires;

    @Nullable
    private String targetSlot;

    /** Lazy holder, the same shape as an interaction Type's: only registration and a codec test build it. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresNpcCondition> CODEC = BuilderCodec.builder(
                        ZigRequiresNpcCondition.class, ZigRequiresNpcCondition::new, SimpleCondition.ABSTRACT_CODEC)
                .documentation("A simple boolean condition that returns whether an entity meets a Requires block "
                        + "(the shape quests use: Factors, Permission, Quests, Custom, AllOf, AnyOf, Not): the target "
                        + "the option is scored against, or the entity locked in TargetSlot. A player is read as "
                        + "themselves; any other entity through its own factors.")
                .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires, (c, parent) -> c.requires = parent.requires)
                .documentation("What the entity must already have or have done for this condition to hold. An "
                        + "unauthored block asks for nothing, so it always holds.")
                .add()
                .appendInherited(new KeyedCodec<>("TargetSlot", Codec.STRING, false),
                        (c, v) -> c.targetSlot = v, c -> c.targetSlot, (c, parent) -> c.targetSlot = parent.targetSlot)
                .documentation("The NPC's marked-target slot whose entity is asked about (LockedTarget, say). Unset, "
                        + "the entity the option is scored against is asked: a combat action's target, or the NPC "
                        + "itself for a state choice. An empty slot does not hold.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresNpcCondition> codec() {
        return Holder.CODEC;
    }

    /** The authored block, null when none was written. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The marked-target slot asked about, null when the scored target is. */
    @Nullable
    public String getTargetSlot() {
        return targetSlot;
    }

    /**
     * The engine's own score ({@code TrueValue} when the entity meets the block, {@code FalseValue} when not),
     * except that nobody to ask (no target, an empty slot, off the world thread, a ref that is gone, a player the
     * runtime cannot name yet) scores the lower of the two. An author inverts this condition by scoring
     * {@code TrueValue} 0 and {@code FalseValue} 1, as vanilla does; left to the engine, a refusal for want of a
     * reading would then score 1 and keep an option meant only for entities that do not meet the block. The lower
     * value never scores above either verdict. A block that asks for nothing holds, so it scores {@code TrueValue}.
     */
    @Override
    public double calculateUtility(int selfIndex, ArchetypeChunk<EntityStore> archetypeChunk,
            Ref<EntityStore> target, CommandBuffer<EntityStore> commandBuffer, EvaluationContext context) {
        if (RequiresCheck.asksNothing(requires)) {
            return trueValue;
        }
        GateQuery query = queryAbout(selfIndex, archetypeChunk, target);
        if (query == null) {
            return Math.min(trueValue, falseValue);
        }
        return RequiresCheck.passes(query, requires) ? trueValue : falseValue;
    }

    /** Does the entity asked about meet the block? Nobody to ask refuses. */
    @Override
    protected boolean evaluate(int selfIndex, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nullable Ref<EntityStore> target, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull EvaluationContext context) {
        return RequiresCheck.passes(queryAbout(selfIndex, archetypeChunk, target), requires);
    }

    /**
     * The question put about the entity in the {@code TargetSlot}, else the scored {@code target}; null when
     * there is nobody to ask ({@link RequiresCheck#query}).
     */
    @Nullable
    private GateQuery queryAbout(int selfIndex, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nullable Ref<EntityStore> target) {
        Ref<EntityStore> about = targetSlot == null || targetSlot.isBlank()
                ? target : marked(selfIndex, archetypeChunk, targetSlot);
        return about == null ? null : RequiresCheck.query(about.getStore(), about);
    }

    /** The entity locked in {@code slot} on the NPC at {@code selfIndex}, or null when the slot is empty. */
    @Nullable
    private static Ref<EntityStore> marked(int selfIndex, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull String slot) {
        MarkedEntitySupport marks = archetypeChunk.getComponent(selfIndex, MarkedEntitySupport.getComponentType());
        return marks == null ? null : marks.getMarkedEntityRef(slot.trim());
    }

    /** A gate walk costs more than any vanilla check, so it is weighed last within its option. */
    @Override
    public int getSimplicity() {
        return HIGH_COST_SIMPLICITY;
    }
}
