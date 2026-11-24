package com.ziggfreed.common.objectives.gate;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.config.taskcondition.TaskConditionAsset;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * A native objective task's condition over a {@code Requires} block: the task counts and completes only while the
 * entity the engine asks about meets it, beside the engine's own {@code SoloInventory} in the task's
 * {@code TaskConditions} (every one must hold).
 *
 * <pre>{@code
 * "TaskConditions": [ { "Type": "ZigRequires",
 *                       "Requires": { "Quests": ["Yourpack_Intro"] } } ]
 * }</pre>
 *
 * <p>Who it asks about is whoever the engine hands the task's conditions, the same entity a {@code SoloInventory}
 * reads: the player who crafted, used a block or a character, reached a location, or whose inventory changed, and
 * for CommonLib's {@code ZigMoment} the player the moment is credited to; but for a kill task the creature killed, so
 * a block naming {@code Quests}, {@code Permission} or a {@code Custom} kind never holds there, a {@code Not} group
 * naming one included, and any other reading reads the creature. A player is read as the runtime's own subject, any
 * other entity
 * through its own factors ({@link RequiresCheck#query}). The engine also asks with nobody at all when a {@code Gather}
 * task starts (to count what its players already carry): a block that asks for something refuses then, so what they
 * carry counts from their next inventory change. A Bounty task never asks its conditions. The objective audit at
 * boot names each of these where it bites ({@code NativeConditionAudit}).
 *
 * <p>On a {@code ZigMoment} task the step's own condition is its {@code Moment}'s {@code While}, the one a quest step
 * writes; this condition is for the engine's own tasks, which have none. A {@code ZigMoment} task that writes both
 * asks the player both, each once.
 *
 * <p>It never throws and never takes anything: {@link #consumeCondition} does nothing, since a gate spends nothing.
 * Registered into the engine's task condition map once the Objectives plugin has set up
 * ({@link ZigRequiresBootstrap#registerObjectiveConditions}).
 */
public final class ZigRequiresTaskCondition extends TaskConditionAsset {

    /** The authored {@code "Type"} name, the same word every {@code ZigRequires} uses. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    private GateSpec requires;

    /** Whether the block names a leaf only a player can meet, worked out once it decodes; never authored or saved. */
    private boolean playerOnly;

    /** Lazy holder, the same shape as an interaction Type's: only registration and a codec test build it. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresTaskCondition> CODEC = BuilderCodec.builder(
                        ZigRequiresTaskCondition.class, ZigRequiresTaskCondition::new)
                .documentation("Lets the task count and complete only while a Requires block holds (the shape quests "
                        + "use: Factors, Permission, Quests, Custom, AllOf, AnyOf, Not). It asks whoever the engine asks "
                        + "a task condition about: the player for a craft, block, character, location, gather or "
                        + "ZigMoment task, but the creature killed for a kill task, where a block naming Quests, "
                        + "Permission or a Custom kind never holds, even inside Not, and any other reading reads the "
                        + "creature. When a Gather task starts it is asked about nobody and refuses, so what the players "
                        + "already carry counts from their next inventory change. A Bounty task never asks it. On a "
                        + "ZigMoment task, write the condition as its Moment's While instead.")
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation("What the entity must already have or have done for the task to count. An unauthored "
                        + "block asks for nothing, so the task counts as it would without this condition.")
                .add()
                .afterDecode(c -> c.playerOnly = !RequiresCheck.playerOnlyLeaves(c.requires).isEmpty())
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresTaskCondition> codec() {
        return Holder.CODEC;
    }

    /** The authored block, null when none was written. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /**
     * Does the entity at {@code ref} meet the block? A block that asks for nothing holds for anyone, asked or not;
     * any other refuses with nobody to ask (a null ref, as a Gather task's start check passes, one that is gone, or
     * a read off the world thread). Asked about anything but a player (a kill task's creature), a block naming a
     * requirement only a player can meet refuses outright, even inside a {@code Not} group
     * ({@link RequiresCheck#holdsWithoutPlayer}). The engine asks it at each count, so the ask is the gate's
     * kept-verdict one.
     */
    @Override
    public boolean isConditionFulfilled(@Nullable ComponentAccessor<EntityStore> componentAccessor,
            @Nullable Ref<EntityStore> ref, @Nullable Set<UUID> objectivePlayers) {
        if (RequiresCheck.asksNothing(requires)) {
            return true;
        }
        GateQuery query = RequiresCheck.query(ref == null ? null : ref.getStore(), ref);
        if (query == null || query.subject() != null) {
            return RequiresCheck.passes(query, requires);
        }
        return RequiresCheck.holdsWithoutPlayer(query, requires, playerOnly);
    }

    /** A gate spends nothing when its task completes. */
    @Override
    public void consumeCondition(@Nullable ComponentAccessor<EntityStore> componentAccessor,
            @Nullable Ref<EntityStore> ref, @Nullable Set<UUID> objectivePlayers) {
    }

    /**
     * Equal to every other {@code ZigRequires} task condition, whatever its block. The engine compares a task's
     * conditions only to decide whether a reload keeps a running task and its count ({@code ObjectiveTaskAsset
     * .matchesAsset}), and a gate decides only whether a later count may land, never what an earlier one meant, so
     * a reload that retunes the block keeps the player's progress and asks the new block from then on, as a
     * retuned {@code While} on a {@code ZigMoment} task does.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ZigRequiresTaskCondition;
    }

    @Override
    public int hashCode() {
        return TYPE_NAME.hashCode();
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigRequiresTaskCondition{requires=" + (requires == null ? "none" : "authored") + "}";
    }
}
