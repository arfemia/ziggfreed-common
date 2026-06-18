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
 * Builder for {@link ActionOpenDialogue}. Reads a {@code Dialogue} string (the id to
 * open), plus optional {@code ContextNpc} (for {@code @self} resolution / header) and
 * {@code DepsKey} (to disambiguate deps providers when more than one consumer
 * registers one), and restricts the action to {@code InteractionInstruction} context,
 * exactly like the engine's {@code BuilderActionOpenBarterShop}.
 */
public class BuilderActionOpenDialogue extends BuilderActionBase {

    @Nonnull
    protected final StringHolder dialogue = new StringHolder();

    @Nonnull
    protected final StringHolder contextNpc = new StringHolder();

    @Nonnull
    protected final StringHolder depsKey = new StringHolder();

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
                "The dialogue id to open (resolved through the consumer's deps provider)", null);
        this.getString(data, "ContextNpc", this.contextNpc, "", null, BuilderDescriptorState.Stable,
                "Optional context NPC id for '@self' action-target resolution and the dialogue header", null);
        this.getString(data, "DepsKey", this.depsKey, "", null, BuilderDescriptorState.Stable,
                "Optional deps-provider key (blank = the default provider) to disambiguate multiple consumers", null);
        this.requireInstructionType(EnumSet.of(InstructionType.Interaction));
        return this;
    }

    public String getDialogue(@Nonnull BuilderSupport support) {
        return this.dialogue.get(support.getExecutionContext());
    }

    public String getContextNpc(@Nonnull BuilderSupport support) {
        return this.contextNpc.get(support.getExecutionContext());
    }

    public String getDepsKey(@Nonnull BuilderSupport support) {
        return this.depsKey.get(support.getExecutionContext());
    }
}
