package com.ziggfreed.common.encounter.types;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.NumberArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSequenceValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNullOrNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;
import com.hypixel.hytale.server.npc.instructions.Sensor;

/**
 * Builds {@link SensorZigFactor}: {@code {"Type": "ZigFactor", "Factor": "ziggfreedcommon:encounter_waves",
 * "Value": [2, 1000000]}} is true once two waves have been signalled; {@code "Factor": "hytale:stat",
 * "Param": "Health"} reads the subject's own stat; a factor a companion mod contributes matches by id.
 */
public class BuilderSensorZigFactor extends BuilderSensorBase {

    protected final StringHolder factor = new StringHolder();
    protected final StringHolder param = new StringHolder();
    protected final NumberArrayHolder value = new NumberArrayHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True while a factor reading for the encounter is inside a range";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Resolves Factor (with Param where the factor takes one) about the fight: the encounter's own "
                + "readings (members, elapsed seconds, phase index, waves, deaths), the portable hytale: readings "
                + "about the bound subject, and any factor another mod contributed by id. A factor nothing can "
                + "answer never matches.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport builderSupport) {
        return new SensorZigFactor(this, builderSupport);
    }

    @Nonnull
    @Override
    public Builder<Sensor> readConfig(@Nonnull JsonElement data) {
        requireString(data, "Factor", factor, StringNotEmptyValidator.get(), BuilderDescriptorState.Stable,
                "The factor id to read", null);
        getString(data, "Param", param, null, StringNullOrNotEmptyValidator.get(), BuilderDescriptorState.Stable,
                "The factor's own parameter, where it takes one (a stat id for hytale:stat)", null);
        requireDoubleRange(data, "Value", value,
                DoubleSequenceValidator.betweenWeaklyMonotonic(-Double.MAX_VALUE, Double.MAX_VALUE),
                BuilderDescriptorState.Stable, "The range the reading must fall in, [min, max] inclusive", null);
        requireInstructionType(EnumSet.of(InstructionType.Encounter));
        return this;
    }

    public String getFactor(@Nonnull BuilderSupport support) {
        return factor.get(support.getExecutionContext());
    }

    public String getParam(@Nonnull BuilderSupport support) {
        return param.get(support.getExecutionContext());
    }

    public double[] getValue(@Nonnull BuilderSupport support) {
        return value.get(support.getExecutionContext());
    }
}
