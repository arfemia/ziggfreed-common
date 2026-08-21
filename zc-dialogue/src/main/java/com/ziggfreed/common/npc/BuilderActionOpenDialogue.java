package com.ziggfreed.common.npc;

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
 * Builder for {@link ActionOpenDialogue}. Reads a {@code Dialogue} string (the id to open) plus an
 * optional {@code ContextNpc} (for {@code @self} resolution and the header), and restricts the
 * action to {@code InteractionInstruction} context, exactly like the engine's
 * {@code BuilderActionOpenBarterShop}.
 */
public class BuilderActionOpenDialogue extends BuilderActionBase {

    @Nonnull
    protected final StringHolder dialogue = new StringHolder();

    @Nonnull
    protected final StringHolder contextNpc = new StringHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Open a branching NPC dialogue page for the interacting player";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return this.getShortDescription();
    }

    @Nonnull
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionOpenDialogue(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    public BuilderActionOpenDialogue readConfig(@Nonnull JsonElement data) {
        this.getString(data, "Dialogue", this.dialogue, "", null, BuilderDescriptorState.Stable,
                "The dialogue id to open, read from the server's one conversation store", null);
        this.getString(data, "ContextNpc", this.contextNpc, "", null, BuilderDescriptorState.Stable,
                "Optional context NPC id for '@self' action-target resolution and the dialogue header", null);
        this.requireInstructionType(EnumSet.of(InstructionType.Interaction));
        return this;
    }

    public String getDialogue(@Nonnull BuilderSupport support) {
        return this.dialogue.get(support.getExecutionContext());
    }

    public String getContextNpc(@Nonnull BuilderSupport support) {
        return this.contextNpc.get(support.getExecutionContext());
    }
}
