package com.ziggfreed.common.npc.placement;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

/**
 * Builder for {@link ActionPlacementInteract}.
 *
 * <p>It reads NO fields at all: everything the action needs comes from the NPC's own placement stamp
 * rather than from the role asset. That is what lets ONE base role serve every placement on the
 * server, whoever shipped it.
 *
 * <p>Restricted to {@code InteractionInstruction} context, like every other press-F action.
 */
public class BuilderActionPlacementInteract extends BuilderActionBase {

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
        this.requireInstructionType(EnumSet.of(InstructionType.Interaction));
        return this;
    }
}
