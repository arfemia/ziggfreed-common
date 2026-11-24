package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.config.triggercondition.ObjectiveLocationTriggerCondition;
import com.hypixel.hytale.builtin.adventure.objectives.markers.objectivelocation.ObjectiveLocationMarker;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * An objective location marker's trigger condition over a {@code Requires} block: the marker sets up its objective
 * for the players near it only while the block holds, beside the engine's own {@code HourRange} and {@code Weather}
 * in the marker's {@code TriggerConditions} (every one must hold).
 *
 * <pre>{@code
 * "TriggerConditions": [ { "Type": "ZigRequires",
 *                          "Requires": { "Factors": [ { "Factor": "yourmod:feature",
 *                                                       "Param": "Breach", "Min": 1 } ] } } ]
 * }</pre>
 *
 * <p>The engine asks a trigger condition about the marker itself, never about a player, and only while a player
 * stands in the marker's exit area and no objective of its own is running. So the block is asked about the place
 * with nobody in it ({@link RequiresCheck#placeQuery}): a server-wide reading (a feature, a season, an installed mod)
 * or one of where the marker stands answers. A block naming {@code Quests}, {@code Permission} or a {@code Custom}
 * kind never holds here, a {@code Not} group naming one included, which the objective audit at boot names; a factor
 * reading of a player reads nothing here, so a {@code Not} group over one never shuts the gate. To gate the objective
 * on who walks up, gate its tasks ({@code ZigRequires} in a task's {@code TaskConditions}).
 *
 * <p>Asked every tick while a player waits nearby, so the ask is the gate's kept-verdict one. Never throws.
 * Registered into the engine's trigger condition map once the Objectives plugin has set up
 * ({@link ZigRequiresBootstrap#registerObjectiveConditions}).
 */
public final class ZigRequiresMarkerCondition extends ObjectiveLocationTriggerCondition {

    /** The authored {@code "Type"} name, the same word every {@code ZigRequires} uses. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    private GateSpec requires;

    /** Whether the block names a leaf only a player can meet, worked out once it decodes; never authored or saved. */
    private boolean playerOnly;

    /** Lazy holder, the same shape as an interaction Type's: only registration builds it. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresMarkerCondition> CODEC = BuilderCodec.builder(
                        ZigRequiresMarkerCondition.class, ZigRequiresMarkerCondition::new)
                .documentation("Lets the marker start its objective only while a Requires block holds (the shape "
                        + "quests use: Factors, Permission, Quests, Custom, AllOf, AnyOf, Not). It is asked about the "
                        + "marker's place with no player in it, so only a server-wide reading (a feature, a season, an "
                        + "installed mod) or one of where the marker stands can meet it. A block naming Quests, "
                        + "Permission or a Custom kind never holds here, even inside Not, and a reading of a player reads "
                        + "nothing here. To gate on who walks up, put ZigRequires in the objective's task conditions "
                        + "instead.")
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation("What must hold for the marker to start its objective. An unauthored block asks for "
                        + "nothing, so the marker starts it as it would without this condition.")
                .add()
                .afterDecode(c -> c.playerOnly = !RequiresCheck.playerOnlyLeaves(c.requires).isEmpty())
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresMarkerCondition> codec() {
        return Holder.CODEC;
    }

    /** The authored block, null when none was written. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /**
     * Does the marker's place meet the block? A block that asks for nothing holds at once; one naming a requirement
     * only a player can meet never holds, even inside a {@code Not} group ({@link RequiresCheck#holdsWithoutPlayer});
     * any other is asked of the world at the marker ({@link RequiresCheck#placeQuery}) and refuses when that cannot
     * be read (a marker that is gone, or a read off the world thread).
     */
    @Override
    public boolean isConditionMet(@Nullable ComponentAccessor<EntityStore> componentAccessor,
            @Nullable Ref<EntityStore> ref, @Nullable ObjectiveLocationMarker objectiveLocationMarker) {
        if (RequiresCheck.asksNothing(requires)) {
            return true;
        }
        return RequiresCheck.holdsWithoutPlayer(RequiresCheck.placeQuery(ref == null ? null : ref.getStore(), ref),
                requires, playerOnly);
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigRequiresMarkerCondition{requires=" + (requires == null ? "none" : "authored") + "}";
    }
}
