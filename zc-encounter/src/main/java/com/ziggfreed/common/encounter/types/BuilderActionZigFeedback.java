package com.ziggfreed.common.encounter.types;

import java.util.EnumSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builds {@link ActionZigFeedback}: {@code {"Type": "ZigFeedback", "Moment": "Encounter_Phase_Changed",
 * "ToMembers": true, "ToWorld": false, "Args": {"phase": "Enraged"}}}.
 */
public class BuilderActionZigFeedback extends BuilderActionBase {

    protected final StringHolder moment = new StringHolder();
    protected final BooleanHolder toMembers = new BooleanHolder();
    protected final BooleanHolder toWorld = new BooleanHolder();
    @Nullable protected JsonElement args;

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Draw an authored FeedbackMoment for the encounter's members";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Fires the FeedbackMoment named by Moment (a Server/ZiggfreedCommon/FeedbackMoments file) to each "
                + "member, or to every player in the world with ToWorld, behind each player's own notification "
                + "preference. Args adds named values the moment's lines can fill; the encounter id, its title, "
                + "the phase, the member count and the elapsed seconds are always carried.";
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionZigFeedback(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull JsonElement data) {
        requireString(data, "Moment", moment, StringNotEmptyValidator.get(), BuilderDescriptorState.Stable,
                "The FeedbackMoment id to draw", null);
        getBoolean(data, "ToMembers", toMembers, true, BuilderDescriptorState.Stable,
                "Draw it for each member of the encounter", null);
        getBoolean(data, "ToWorld", toWorld, false, BuilderDescriptorState.Stable,
                "Draw it for every player in the encounter's world", null);
        this.args = getOptionalJsonElement(data, "Args");
        requireInstructionType(EnumSet.of(InstructionType.Encounter, InstructionType.EncounterStateTransitions));
        return this;
    }

    public String getMoment(@Nonnull BuilderSupport support) {
        return moment.get(support.getExecutionContext());
    }

    public boolean getToMembers(@Nonnull BuilderSupport support) {
        return toMembers.get(support.getExecutionContext());
    }

    public boolean getToWorld(@Nonnull BuilderSupport support) {
        return toWorld.get(support.getExecutionContext());
    }

    @Nullable
    public JsonElement getArgs() {
        return args;
    }
}
