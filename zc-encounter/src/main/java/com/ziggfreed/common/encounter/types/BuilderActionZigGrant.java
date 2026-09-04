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
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builds {@link ActionZigGrant}: {@code {"Type": "ZigGrant", "Loot": {...}, "ToMembers": true,
 * "ToKiller": false, "QueueIfOffline": true}}.
 *
 * <p>{@code Loot} is the library's loot reference group (shared tables by id, rolls written inline,
 * or both), kept as authored JSON and decoded when the action is built.
 */
public class BuilderActionZigGrant extends BuilderActionBase {

    protected final BooleanHolder toMembers = new BooleanHolder();
    protected final BooleanHolder toKiller = new BooleanHolder();
    protected final BooleanHolder queueIfOffline = new BooleanHolder();
    @Nullable protected JsonElement loot;

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Pay the encounter's credited participants a loot reference, scaled by each one's share";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Settles the run's participation credit and pays each credited participant the Loot group "
                + "(Lootables by id and/or inline Rolls), keeping each roll with a chance equal to that "
                + "participant's share of the top contributor's. ToKiller adds the last hitter at a full share; "
                + "QueueIfOffline parks an offline participant's payout for their next connect.";
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionZigGrant(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Builder<Action> readConfig(@Nonnull JsonElement data) {
        getBoolean(data, "ToMembers", toMembers, true, BuilderDescriptorState.Stable,
                "Pay every credited participant", null);
        getBoolean(data, "ToKiller", toKiller, false, BuilderDescriptorState.Stable,
                "Also pay whoever landed the killing blow, at a full share", null);
        getBoolean(data, "QueueIfOffline", queueIfOffline, true, BuilderDescriptorState.Stable,
                "Park an offline participant's payout for their next connect", null);
        // Read through the helper so the key counts as one this builder asked for.
        this.loot = getOptionalJsonElement(data, "Loot");
        requireInstructionType(EnumSet.of(InstructionType.Encounter, InstructionType.EncounterStateTransitions));
        return this;
    }

    public boolean getToMembers(@Nonnull BuilderSupport support) {
        return toMembers.get(support.getExecutionContext());
    }

    public boolean getToKiller(@Nonnull BuilderSupport support) {
        return toKiller.get(support.getExecutionContext());
    }

    public boolean getQueueIfOffline(@Nonnull BuilderSupport support) {
        return queueIfOffline.get(support.getExecutionContext());
    }

    @Nullable
    public JsonElement getLoot() {
        return loot;
    }
}
