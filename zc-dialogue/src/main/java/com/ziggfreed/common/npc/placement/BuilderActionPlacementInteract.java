package com.ziggfreed.common.npc.placement;

import java.util.EnumSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 * <p>Everything this action needs normally comes from the NPC's own placement stamp rather than from
 * the role asset, which is what lets ONE base role serve every placement on the server, whoever
 * shipped it. The two OPTIONAL fields below are the answer for an NPC standing here by some other
 * route - spawned by a command, an egg, a prefab, another mod - which carries no stamp and so has no
 * placement to read. Authoring one gives the role its own answer to "what does pressing F open when
 * nobody placed me", and a placement's own {@code Interact} still wins wherever there is one.
 *
 * <ul>
 *   <li>{@code Dialogue} - the conversation to open, named by its file id. Computable, so a role
 *       built as a native {@code Variant} can bind it per character through the template's own
 *       {@code Parameters}.</li>
 *   <li>{@code Open} - any destination a mod on this server registered, in the same one-word or
 *       {@code {"Type": ...}} spelling a placement's {@code Interact.Open} uses. Author this OR
 *       {@code Dialogue}; with both, this one runs. NOT computable: a destination is read as the
 *       authored value itself, so a {@code {"Compute": "..."}} binding here is not a destination and
 *       opens nothing. A template whose variants each open a different screen puts the varying part
 *       in {@code Dialogue}, or gives each variant its own full role body.</li>
 * </ul>
 *
 * <p>Restricted to {@code InteractionInstruction} context, like every other press-F action.
 */
public class BuilderActionPlacementInteract extends BuilderActionBase {

    /** The conversation a stamp-less NPC of this role opens. Computable, so a Variant can bind it. */
    protected final StringHolder dialogue = new StringHolder();

    /**
     * The destination a stamp-less NPC of this role opens, kept as raw JSON.
     *
     * <p>It is NOT decoded here. A role asset is read once at boot, and decoding a destination marks
     * the process-wide vocabulary as having been read, which turns any later registration into a
     * warning about content that was already parsed. The action decodes it on first use instead, by
     * which time every mod's {@code setup()} has certainly run.
     */
    @Nullable
    protected JsonElement open;

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
        getString(data, "Dialogue", this.dialogue, null, null, BuilderDescriptorState.Stable,
                "The conversation an NPC of this role opens when nothing placed it. A placement's own"
                        + " Interact still wins wherever there is one.", null);
        // Read through the helper rather than off the object directly: it registers the key as one
        // this builder asked for, so authoring Open is not reported as an unexpected field.
        this.open = getOptionalJsonElement(data, "Open");
        return this;
    }

    /** The authored conversation id, resolved through {@code support} so a Compute binding applies. */
    @Nullable
    public String getDialogue(@Nonnull BuilderSupport support) {
        return this.dialogue.get(support.getExecutionContext());
    }

    /** The authored destination as raw JSON, for the action to decode on first use. */
    @Nullable
    public JsonElement getOpen() {
        return this.open;
    }
}
