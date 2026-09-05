package com.ziggfreed.common.encounter.types;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;
import com.hypixel.hytale.server.npc.instructions.Sensor;

/**
 * Builds {@link SensorZigRested}: {@code {"Type": "ZigRested"}} is true while the site may raise its
 * boss again, false while it rests. It takes no keys of its own; the rest itself is the binding
 * row's {@code Timing.Rest}, stamped on the encounter entity at each defeat.
 */
public class BuilderSensorZigRested extends BuilderSensorBase {

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True while the encounter's site has rested since its last defeat";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Reads the rest the library stamped on this encounter entity at its last defeat (the binding row's "
                + "Timing.Rest, measured on the world's clock) and matches once the clock is past it, or straight "
                + "away when nothing was ever stamped. Gate TriggerSpawners and a no-show timeout on it so a resting "
                + "site waits quietly instead of trying its marker.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport builderSupport) {
        return new SensorZigRested(this);
    }

    @Nonnull
    @Override
    public Builder<Sensor> readConfig(@Nonnull JsonElement data) {
        requireInstructionType(EnumSet.of(InstructionType.Encounter));
        return this;
    }
}
