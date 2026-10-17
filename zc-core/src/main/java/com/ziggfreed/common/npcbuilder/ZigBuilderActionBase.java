package com.ziggfreed.common.npcbuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;

/**
 * An NPC builder action (a role's or an encounter script's {@code Type}) that keeps a field as authored JSON
 * for a later decode, the way the engine's own field getters read theirs.
 *
 * <p>The engine reads every builder's {@code readConfig} three ways: loading a file (with its JSON), building
 * the builder's descriptor, and building its schema for the Asset Editor ({@code BuilderBase.toSchema}, which
 * passes null data with both modes on). The engine's getters ({@code getBoolean}, {@code getCodecObject} and
 * the rest) register the field and return in the last two; a raw {@code getOptionalJsonElement} read does not,
 * and dereferences the null data, which stops the editor's whole schema pass. {@link #getOptionalRawJson} is
 * that read done the getters' way. The schema it registers accepts any JSON: the builder's schema allows no
 * other keys, so a field left out of it would read as unknown in the editor.
 */
public abstract class ZigBuilderActionBase extends BuilderActionBase {

    /**
     * The field {@code name} as authored, or null when it is absent or the engine is building this builder's
     * schema or descriptor, where it registers the field (any JSON, described by {@code shortDescription})
     * instead of reading it. Reading it marks the key as one this builder asked for, so authoring it is never
     * reported as an unexpected field.
     */
    @Nullable
    protected JsonElement getOptionalRawJson(@Nullable JsonElement data, @Nonnull String name,
                                             @Nonnull String shortDescription) {
        if (isCreatingSchema()) {
            Schema any = new Schema();
            any.setTitle(name);
            any.setDescription(shortDescription);
            builderSchema.getProperties().put(name, any);
            return null;
        }
        if (isCreatingDescriptor()) {
            builderDescriptor.addAttribute(name, JsonElement.class.getSimpleName(), BuilderDescriptorState.Stable,
                    shortDescription, null).optional((String) null);
            return null;
        }
        return getOptionalJsonElement(data, name);
    }
}
