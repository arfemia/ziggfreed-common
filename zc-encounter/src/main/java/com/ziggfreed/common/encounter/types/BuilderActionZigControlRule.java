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
 * Builds {@link ActionZigControlRule}: {@code {"Type": "ZigControlRule", "TargetSlot": "Boss", "Effect":
 * "MyPack_Boss_Untauntable"}}. Without a slot, the triggering sensor's target, else the binding row's subject; an
 * empty or absent {@code Effect} only takes the boss's standing rules off, back to its own default.
 */
public class BuilderActionZigControlRule extends BuilderActionBase {

    /** The key naming the slot of the boss whose rules change. */
    public static final String TARGET_SLOT = "TargetSlot";

    /** The key naming the rule effect to put on, empty for none. */
    public static final String EFFECT = "Effect";

    protected final StringHolder targetSlot = new StringHolder();
    protected final StringHolder effect = new StringHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Set the encounter boss's crowd-control rule for this phase";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Takes every standing (infinite) crowd-control rule effect off the entity in TargetSlot (or the "
                + "triggering sensor's target, or the bound subject) and puts Effect on: an effect whose Tags name "
                + "ZigControlLands or ZigControlShrugs, such as the library's Zig_Control_Untauntable, so the boss "
                + "takes or shrugs those kinds of control until the next rule. An empty Effect takes the rules off "
                + "and leaves the boss's own default. A timed rule effect (a window) the fight put on stays until it "
                + "runs out.";
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionZigControlRule(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull JsonElement data) {
        getString(data, TARGET_SLOT, targetSlot, null, StringNullOrNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "The target slot holding the boss; omit it for the sensor's target or the bound subject", null);
        // No validator: an empty Effect is the way back to the boss's own default.
        getString(data, EFFECT, effect, "", null, BuilderDescriptorState.Stable,
                "The rule effect to put on; empty takes the standing rules off and puts none on", null);
        requireInstructionType(EnumSet.of(InstructionType.Encounter, InstructionType.EncounterStateTransitions));
        return this;
    }

    public String getTargetSlot(@Nonnull BuilderSupport support) {
        return targetSlot.get(support.getExecutionContext());
    }

    public String getEffect(@Nonnull BuilderSupport support) {
        return effect.get(support.getExecutionContext());
    }
}
