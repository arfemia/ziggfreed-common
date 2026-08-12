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
 * Builder for {@link ActionTalkCredit}. Reads {@code Npc} (the character id to credit, required in
 * practice - a blank one credits nothing) and an optional {@code Qualifier}, and restricts the action
 * to an {@code InteractionInstruction} exactly like the engine's own barter-shop action, so it runs
 * on a press-F rather than on some passing behaviour tick.
 */
public class BuilderActionTalkCredit extends BuilderActionBase {

    @Nonnull
    protected final StringHolder npc = new StringHolder();

    @Nonnull
    protected final StringHolder qualifier = new StringHolder();

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Credit a conversation with a character, for an NPC that has no dialogue of its own";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return this.getShortDescription();
    }

    @Nonnull
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionTalkCredit(this, builderSupport);
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    public BuilderActionTalkCredit readConfig(@Nonnull JsonElement data) {
        this.getString(data, "Npc", this.npc, "", null, BuilderDescriptorState.Stable,
                "The character id to credit. Required: a blank one credits nothing, because a role "
                        + "cannot know who it is, and guessing would credit every NPC using it", null);
        this.getString(data, "Qualifier", this.qualifier, "", null, BuilderDescriptorState.Stable,
                "Optional secondary label passed through to whoever counts the conversation", null);
        this.requireInstructionType(EnumSet.of(InstructionType.Interaction));
        return this;
    }

    public String getNpc(@Nonnull BuilderSupport support) {
        return this.npc.get(support.getExecutionContext());
    }

    public String getQualifier(@Nonnull BuilderSupport support) {
        return this.qualifier.get(support.getExecutionContext());
    }
}
