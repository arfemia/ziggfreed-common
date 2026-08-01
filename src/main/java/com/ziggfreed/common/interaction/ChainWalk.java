package com.ziggfreed.common.interaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/**
 * The result of one {@link ChainWalker} walk: the visited nodes in pre-order plus every way the
 * walk was cut short. Immutable; always usable, even for a walk that never started (a null/blank
 * root id or a root id that failed to resolve still yields a valid, empty {@link ChainWalk}).
 */
public final class ChainWalk {

    @Nonnull
    private final String rootId;
    private final boolean rootResolved;
    @Nonnull
    private final List<ChainNode> nodes;
    private final int maxDepthReached;
    private final boolean cycleDetected;
    @Nullable
    private final ChainNode cycleAt;
    private final boolean depthExceeded;
    private final boolean nodeLimitExceeded;
    private final boolean aborted;
    @Nullable
    private final String abortReason;

    private ChainWalk(@Nonnull Builder builder) {
        this.rootId = builder.rootId;
        this.rootResolved = builder.rootResolved;
        this.nodes = Collections.unmodifiableList(new ArrayList<>(builder.nodes));
        this.maxDepthReached = builder.maxDepthReached;
        this.cycleDetected = builder.cycleDetected;
        this.cycleAt = builder.cycleAt;
        this.depthExceeded = builder.depthExceeded;
        this.nodeLimitExceeded = builder.nodeLimitExceeded;
        this.aborted = builder.aborted;
        this.abortReason = builder.abortReason;
    }

    @Nonnull
    public static Builder builder(@Nullable String rootId) {
        return new Builder(rootId != null ? rootId : "");
    }

    @Nonnull
    public String rootId() {
        return rootId;
    }

    /** {@code false} = the root id did not resolve in the {@code RootInteraction} store. */
    public boolean rootResolved() {
        return rootResolved;
    }

    /** Pre-order, immutable, possibly empty. */
    @Nonnull
    public List<ChainNode> nodes() {
        return nodes;
    }

    public int maxDepthReached() {
        return maxDepthReached;
    }

    /** A node was re-entered on its own path - the stack-overflow shape the engine has no guard for. */
    public boolean cycleDetected() {
        return cycleDetected;
    }

    @Nullable
    public ChainNode cycleAt() {
        return cycleAt;
    }

    /** The depth cap stopped the walk. */
    public boolean depthExceeded() {
        return depthExceeded;
    }

    /** The node-count cap stopped the walk. */
    public boolean nodeLimitExceeded() {
        return nodeLimitExceeded;
    }

    /**
     * The engine walk threw and the result is PARTIAL. The commonest cause is an unresolvable
     * child interaction id: {@code InteractionManager.walkInteraction} throws {@code
     * IllegalArgumentException("Failed to find interaction: <id>")} rather than skipping it.
     */
    public boolean aborted() {
        return aborted;
    }

    @Nullable
    public String abortReason() {
        return abortReason;
    }

    /** {@code true} when any visited node is assignable to {@code type}. Null-safe on both sides. */
    public boolean containsType(@Nullable Class<? extends Interaction> type) {
        if (type == null) {
            return false;
        }
        for (ChainNode node : nodes) {
            Interaction interaction = node.interaction();
            if (interaction != null && type.isInstance(interaction)) {
                return true;
            }
        }
        return false;
    }

    /** Every visited node assignable to {@code type}, in visit order. Never null. */
    @Nonnull
    public <T extends Interaction> List<T> nodesOfType(@Nullable Class<T> type) {
        if (type == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        for (ChainNode node : nodes) {
            Interaction interaction = node.interaction();
            if (interaction != null && type.isInstance(interaction)) {
                result.add(type.cast(interaction));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    @Nonnull
    public String toString() {
        return "ChainWalk{rootId='" + rootId + "', rootResolved=" + rootResolved
                + ", nodes=" + nodes.size() + ", cycleDetected=" + cycleDetected
                + ", depthExceeded=" + depthExceeded + ", nodeLimitExceeded=" + nodeLimitExceeded
                + ", aborted=" + aborted + "}";
    }

    public static final class Builder {
        @Nonnull
        private final String rootId;
        private boolean rootResolved;
        @Nonnull
        private final List<ChainNode> nodes = new ArrayList<>();
        private int maxDepthReached;
        private boolean cycleDetected;
        @Nullable
        private ChainNode cycleAt;
        private boolean depthExceeded;
        private boolean nodeLimitExceeded;
        private boolean aborted;
        @Nullable
        private String abortReason;

        private Builder(@Nonnull String rootId) {
            this.rootId = rootId;
        }

        @Nonnull
        public Builder rootResolved(boolean rootResolved) {
            this.rootResolved = rootResolved;
            return this;
        }

        @Nonnull
        public Builder node(@Nullable ChainNode node) {
            if (node != null) {
                this.nodes.add(node);
                if (node.depth() > this.maxDepthReached) {
                    this.maxDepthReached = node.depth();
                }
            }
            return this;
        }

        /** Also sets {@link #cycleDetected()}. */
        @Nonnull
        public Builder cycleAt(@Nullable ChainNode node) {
            this.cycleAt = node;
            this.cycleDetected = node != null;
            return this;
        }

        @Nonnull
        public Builder depthExceeded(boolean depthExceeded) {
            this.depthExceeded = depthExceeded;
            return this;
        }

        @Nonnull
        public Builder nodeLimitExceeded(boolean nodeLimitExceeded) {
            this.nodeLimitExceeded = nodeLimitExceeded;
            return this;
        }

        /** Null reason clears the flag. */
        @Nonnull
        public Builder aborted(@Nullable String reason) {
            this.abortReason = reason;
            this.aborted = reason != null;
            return this;
        }

        @Nonnull
        public ChainWalk build() {
            return new ChainWalk(this);
        }
    }
}
