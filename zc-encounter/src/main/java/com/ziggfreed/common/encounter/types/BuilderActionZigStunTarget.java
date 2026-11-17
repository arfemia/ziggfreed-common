package com.ziggfreed.common.encounter.types;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNullOrNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builds {@link ActionZigStunTarget}: {@code {"Type": "ZigStunTarget", "TargetSlot": "Boss", "Duration": 5,
 * "Effect": "MyPack_Boss_Exposed"}}. Without a slot, the target the triggering sensor found is stunned, else the
 * binding row's subject; without an effect, the library's own stun.
 */
public class BuilderActionZigStunTarget extends BuilderActionBase {

    protected final StringHolder targetSlot = new StringHolder();
    protected final DoubleHolder duration = new DoubleHolder();
    protected final StringHolder effect = new StringHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Stun the encounter's boss for a window";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Stuns the entity in TargetSlot (or the triggering sensor's target, or the bound subject) for Duration "
                + "seconds the way the library's ZigStun does: what it was doing stops, and it cannot move or attack "
                + "until the stun ends. Effect names the stun effect to put on it in place of the library's "
                + "Zig_Stunned, built on the game's Stun, so a boss's breakdown can wear its own look and its own "
                + "damage taken; a stun effect tagged ZigWeakSpot makes every hit on it a weak spot while it lasts.";
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionZigStunTarget(this, builderSupport);
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
                "The target slot holding the entity to stun; omit it for the sensor's target or the bound subject",
                null);
        getDouble(data, "Duration", duration, ActionZigStunTarget.DEFAULT_DURATION, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "How long the stun lasts, in seconds", null);
        getString(data, "Effect", effect, null, StringNullOrNotEmptyValidator.get(), BuilderDescriptorState.Stable,
                "The stun effect to put on the entity; omit it for the library's Zig_Stunned", null);
        requireInstructionType(EnumSet.of(InstructionType.Encounter, InstructionType.EncounterStateTransitions));
        return this;
    }

    public String getTargetSlot(@Nonnull BuilderSupport support) {
        return targetSlot.get(support.getExecutionContext());
    }

    public double getDuration(@Nonnull BuilderSupport support) {
        return duration.get(support.getExecutionContext());
    }

    public String getEffect(@Nonnull BuilderSupport support) {
        return effect.get(support.getExecutionContext());
    }
}
