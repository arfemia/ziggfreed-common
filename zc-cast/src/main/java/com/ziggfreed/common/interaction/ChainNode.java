package com.ziggfreed.common.interaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/**
 * One visited node of a {@link ChainWalker} walk. Immutable.
 *
 * <p>{@link #interaction()} is deliberately {@code @Nullable}: {@link #of} accepts a null
 * interaction so this value type stays constructible in a unit JVM, where an {@code Interaction}
 * cannot be instantiated (its class-init chains into the RangeValidator/HytaleLogger trap - see
 * {@link ChainWalker}). A test builds synthetic nodes with {@code ChainNode.of(null, "id", depth,
 * "TAG")}; only {@link ChainWalker} itself ever passes a real engine interaction.
 */
public final class ChainNode {

    @Nullable
    private final Interaction interaction;
    @Nonnull
    private final String id;
    private final int depth;
    @Nonnull
    private final String tag;

    private ChainNode(@Nullable Interaction interaction, @Nonnull String id, int depth, @Nonnull String tag) {
        this.interaction = interaction;
        this.id = id;
        this.depth = depth;
        this.tag = tag;
    }

    /**
     * @param interaction the engine node, or {@code null} for a synthetic/root entry.
     * @param id           the caller-supplied id, used only when {@code interaction} is null or
     *                     its own {@link Interaction#getId()} is null.
     * @param depth        0 = a direct child of the root interaction.
     * @param tag          a caller-rendered label for the engine {@code CollectorTag} this node was
     *                     visited under, or {@code null} to render {@code "ROOT"}.
     */
    @Nonnull
    public static ChainNode of(@Nullable Interaction interaction, @Nullable String id, int depth,
                               @Nullable String tag) {
        String resolvedId = "";
        if (interaction != null) {
            String interactionId = interaction.getId();
            resolvedId = interactionId != null ? interactionId : (id != null ? id : "");
        } else if (id != null) {
            resolvedId = id;
        }
        String resolvedTag = tag != null ? tag : "ROOT";
        return new ChainNode(interaction, resolvedId, depth, resolvedTag);
    }

    @Nullable
    public Interaction interaction() {
        return interaction;
    }

    /** {@code interaction.getId()} captured at visit time, else the caller-supplied id, else "". */
    @Nonnull
    public String id() {
        return id;
    }

    /** 0 = a direct child of the root interaction. */
    public int depth() {
        return depth;
    }

    /** The engine {@code CollectorTag} rendered as a label, or {@code "ROOT"}. */
    @Nonnull
    public String tag() {
        return tag;
    }

    @Override
    @Nonnull
    public String toString() {
        return "ChainNode{id='" + id + "', depth=" + depth + ", tag='" + tag + "'}";
    }
}
