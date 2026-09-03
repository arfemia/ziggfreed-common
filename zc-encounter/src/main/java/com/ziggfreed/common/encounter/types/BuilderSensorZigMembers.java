package com.ziggfreed.common.encounter.types;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.NumberArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.IntSequenceValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;
import com.hypixel.hytale.server.npc.instructions.Sensor;

/**
 * Builds {@link SensorZigMembers}: {@code {"Type": "ZigMembers", "Count": [0, 0]}} is true while
 * nobody is inside, {@code [3, 99]} while three or more are. The wipe test a script cannot otherwise
 * express.
 */
public class BuilderSensorZigMembers extends BuilderSensorBase {

    protected final NumberArrayHolder count = new NumberArrayHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True while the encounter's live member count is inside a range";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Reads the encounter's own member roster (the players its Player sensor with the EncounterMembers "
                + "collector is tracking, plus anyone a spawn call seeded) and matches while the count is inside "
                + "Count, inclusive at both ends.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport builderSupport) {
        return new SensorZigMembers(this, builderSupport);
    }

    @Nonnull
    @Override
    public Builder<Sensor> readConfig(@Nonnull JsonElement data) {
        requireIntRange(data, "Count", count, IntSequenceValidator.betweenWeaklyMonotonic(0, Integer.MAX_VALUE),
                BuilderDescriptorState.Stable, "The allowed number of members, [min, max] inclusive", null);
        requireInstructionType(EnumSet.of(InstructionType.Encounter));
        return this;
    }

    public int[] getCount(@Nonnull BuilderSupport support) {
        return count.getIntArray(support.getExecutionContext());
    }
}
