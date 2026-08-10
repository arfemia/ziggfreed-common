package com.ziggfreed.common.npc.placement;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builder for {@link ActionPlacementInteract}.
 *
 * <p>It reads exactly one optional field, {@code DepsKey}, because everything else the action needs
 * comes from the NPC's own placement stamp rather than from the role asset. That is what lets ONE
 * base role serve every placement on the server.
 *
 * <p>Restricted to {@code InteractionInstruction} context, like every other press-F action.
 */
public class BuilderActionPlacementInteract extends BuilderActionBase {

    @Nonnull
    protected final StringHolder depsKey = new StringHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Run this placed NPC's authored interaction: its dialogue, and its namespaced bindings";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return this.getShortDescription();
    }

    @Nonnull
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionPlacementInteract(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    public BuilderActionPlacementInteract readConfig(@Nonnull JsonElement data) {
        this.getString(data, "DepsKey", this.depsKey, "", null, BuilderDescriptorState.Stable,
                "Optional dialogue-deps provider key (blank = the default provider), to disambiguate "
                        + "several consumers on one server", null);
        this.requireInstructionType(EnumSet.of(InstructionType.Interaction));
        return this;
    }

    public String getDepsKey(@Nonnull BuilderSupport support) {
        return this.depsKey.get(support.getExecutionContext());
    }
}
