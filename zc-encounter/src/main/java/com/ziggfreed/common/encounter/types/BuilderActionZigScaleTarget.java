package com.ziggfreed.common.encounter.types;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNullOrNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builds {@link ActionZigScaleTarget}: {@code {"Type": "ZigScaleTarget", "TargetSlot": "Boss"}}.
 * Without a slot, the target the triggering sensor found is scaled, else the binding row's subject.
 */
public class BuilderActionZigScaleTarget extends BuilderActionBase {

    protected final StringHolder targetSlot = new StringHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Apply the binding row's health scale to the encounter's subject now";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Puts the binding row's party, run and power health multiplier on the entity in TargetSlot (or the "
                + "triggering sensor's target, or the bound subject) at once, rather than waiting for the next "
                + "tick; the same keyed modifier the tick applies and reconciles.";
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionZigScaleTarget(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull JsonElement data) {
        getString(data, "TargetSlot", targetSlot, null, StringNullOrNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "The target slot holding the entity to scale; omit it for the sensor's target or the bound subject",
                null);
        requireInstructionType(EnumSet.of(InstructionType.Encounter, InstructionType.EncounterStateTransitions));
        return this;
    }

    public String getTargetSlot(@Nonnull BuilderSupport support) {
        return targetSlot.get(support.getExecutionContext());
    }
}
