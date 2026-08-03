package com.ziggfreed.common.interaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.Collector;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.CollectorTag;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.StringTag;
import com.ziggfreed.common.util.SafeLog;

/**
 * Transitive, CYCLE-GUARDED, fail-soft resolution of a {@code RootInteraction} chain into its
 * reachable interaction nodes - for validators (does this chain contain a damage node? does every
 * ref resolve?) and description renderers (walk the chain, render a fragment per node).
 *
 * <p><b>The engine ships no cycle or depth guard anywhere</b> (compile() recurses raw and the
 * chain tick loop has no cap). A self-referencing fragment stack-overflows the server at load.
 * This walker is the guard, and a public fragment library is unsafe without it.
 *
 * <p><b>Mechanism.</b> The walk rides the engine's own {@code
 * InteractionManager.walkChain(Collector, InteractionType, InteractionContext, RootInteraction)}
 * with a guarding {@code Collector}, so it follows each concrete node's OWN declared child refs
 * (including fork edges such as {@code SelectInteraction}'s {@code HitEntity}/{@code
 * HitEntityRules}) - strictly more than the flat compiled {@code getOperation(i)} view used by
 * {@code com.ziggfreed.mmoskilltree.ability.NativeChainAudit} (the narrower prior art this
 * generalizes past), which is blind to forks that dispatch to a separately-compiled chain.
 *
 * <p><b>Two engine limitations are surfaced, not hidden.</b>
 * <ol>
 *   <li>The engine {@code Collector} contract has no "prune this branch" return - {@code
 *       walkInteraction} does {@code if (collector.collect(...)) return true;} and that {@code
 *       true} propagates all the way out, so a detected cycle or an exceeded cap ENDS THE ENTIRE
 *       WALK, not just the offending branch. The result carries the flag plus everything collected
 *       so far, never a partial-branch skip.</li>
 *   <li>{@code walkInteraction} THROWS {@code IllegalArgumentException("Failed to find
 *       interaction: <id>")} on an unresolvable child id rather than skipping it; this is caught
 *       and reported as {@link ChainWalk#aborted()} + {@link ChainWalk#abortReason()} with the
 *       engine message, never silently swallowed.</li>
 * </ol>
 *
 * <p><b>Cycle detection is IDENTITY-based on the current path</b>, which is sound because
 * interaction assets are decode-once immutable singletons shared by every firing entity - the same
 * {@code Interaction} instance re-appearing on its own ancestor path can only mean a cycle in the
 * authored content, never two different casts colliding.
 *
 * <p>Not reachable from a unit JVM once it touches the asset stores (they need an engine
 * bootstrap); the argument guards short-circuit before that, and every engine touch beyond the
 * guards is inside one {@code catch (Throwable)}.
 */
public final class ChainWalker {

    public static final int DEFAULT_MAX_DEPTH = 32;
    public static final int DEFAULT_MAX_NODES = 512;

    private ChainWalker() {
    }

    /** As {@link #walk(String, InteractionType, int, int)} with the default depth/node caps. */
    @Nonnull
    public static ChainWalk walk(@Nullable String rootInteractionId, @Nullable InteractionType type) {
        return walk(rootInteractionId, type, DEFAULT_MAX_DEPTH, DEFAULT_MAX_NODES);
    }

    /** As {@link #walk(String, InteractionType, int, int, InteractionContext)} with no live context. */
    @Nonnull
    public static ChainWalk walk(@Nullable String rootInteractionId, @Nullable InteractionType type,
                                 int maxDepth, int maxNodes) {
        return walk(rootInteractionId, type, maxDepth, maxNodes, null);
    }

    /**
     * @param context a live context to walk under, or {@code null} to use {@code
     *                InteractionContext.withoutEntity()}. A static, entity-less walk cannot resolve
     *                item {@code InteractionVars}, so a {@code Replace} slot may take its default
     *                branch; pass a live context when that matters.
     */
    @Nonnull
    public static ChainWalk walk(@Nullable String rootInteractionId, @Nullable InteractionType type,
                                 int maxDepth, int maxNodes, @Nullable InteractionContext context) {
        ChainWalk.Builder builder = ChainWalk.builder(rootInteractionId);
        if (rootInteractionId == null || rootInteractionId.isBlank() || type == null) {
            return builder.rootResolved(false).build();
        }
        try {
            RootInteraction root = RootInteraction.getAssetMap().getAsset(rootInteractionId);
            if (root == null) {
                return builder.rootResolved(false).build();
            }
            builder.rootResolved(true);

            InteractionContext effectiveContext = context != null ? context : InteractionContext.withoutEntity();
            GuardingCollector collector = new GuardingCollector(builder, maxDepth, maxNodes);
            try {
                InteractionManager.walkChain(collector, type, effectiveContext, root);
                walkEngineGapSlots(collector, effectiveContext);
            } catch (IllegalArgumentException e) {
                String reason = e.getMessage() != null ? e.getMessage() : "unresolvable interaction id";
                SafeLog.warn("[interaction] chain walk aborted for '" + rootInteractionId + "': " + reason);
                builder.aborted(reason);
            }
        } catch (Throwable t) {
            SafeLog.warn("[interaction] chain walk failed for '" + rootInteractionId + "'", t);
            builder.aborted("chain walk failed: " + t.getMessage());
        }
        return builder.build();
    }

    /**
     * SUPPLEMENTAL pass over the engine's blind slots ({@link EngineWalkGaps}): the engine's own
     * {@code interaction.walk()} coverage misses several child-bearing codec slots (a Type that
     * adds a child field without overriding {@code walk()} inherits the Next/Failed-only
     * {@code SimpleInteraction.walk} - {@code ApplyForce.GroundNext}/{@code CollisionNext},
     * {@code Charging.Forks}, {@code MovementCondition}'s 8 direction slots,
     * {@code RunRootInteraction}'s entire payload, ...). Without this pass a validator riding the
     * walk is blind to whole authored subtrees (the motivating bug: native {@code Selector} AOE
     * sweeps hidden inside {@code GroundNext} continuations never reached the ability validator).
     *
     * <p>Runs AFTER the engine walk, treating the collected node list as a growing worklist: for
     * each node with known gap slots, every referenced id is resolved against the STORE its slot
     * codec declares ({@code SlotRef.rootRef} - never a cross-store guess: ids like
     * {@code Goblin_Duke_Magic_1} exist in BOTH stores) and walked through the SAME guarding
     * collector, so caps and cycle budgets still apply and newly discovered nodes get their own
     * gap slots processed too. A missing id follows the ENGINE's own per-slot contract
     * ({@code SlotRef.tolerant}): the {@code get*OrUnknown}-resolved slots degrade to skip + one
     * guarded warn, only the {@code Interaction.CHILD_ASSET_CODEC} slots share
     * {@code walkInteraction}'s throwing abort.
     *
     * <p>Known bounds, deliberate: an identity set caps each node's gap-slot EXPANSION at once,
     * so a cycle through a gap slot terminates - but a re-visited subtree IS re-collected (its
     * nodes appear again in {@code walk.nodes()}; a cross-supplement cycle therefore surfaces as
     * the node budget, not {@code cycleAt}, since the ancestor is no longer on the live path).
     * Depth for supplemented subtrees restarts near zero (the engine path context has unwound) -
     * node identity and the node budget, not depth, are the meaningful guards here.
     */
    private static void walkEngineGapSlots(@Nonnull GuardingCollector collector,
                                           @Nonnull InteractionContext context) {
        Set<Interaction> supplemented = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Interaction> order = collector.collectedOrder;
        for (int i = 0; i < order.size() && !collector.stopped; i++) {
            Interaction node = order.get(i);
            if (!supplemented.add(node)) {
                continue;
            }
            for (EngineWalkGaps.SlotRef ref : EngineWalkGaps.missedRefsOf(node)) {
                if (collector.stopped) {
                    return;
                }
                if (ref.rootRef()) {
                    RootInteraction gapRoot = RootInteraction.getAssetMap().getAsset(ref.id());
                    if (gapRoot == null) {
                        if (!ref.tolerant()) {
                            throw new IllegalArgumentException("Failed to find interaction: " + ref.id());
                        }
                        SafeLog.fine("[interaction] gap-slot root '" + ref.id()
                                + "' unresolved (engine-tolerant slot) - skipped");
                        continue;
                    }
                    if (InteractionManager.walkInteractions(collector, context, ref.tag(), gapRoot.getInteractionIds())) {
                        return;
                    }
                    continue;
                }
                if (Interaction.getAssetMap().getAsset(ref.id()) == null && ref.tolerant()) {
                    SafeLog.fine("[interaction] gap-slot interaction '" + ref.id()
                            + "' unresolved (engine-tolerant slot) - skipped");
                    continue;
                }
                // Strict Interaction slot: walkInteraction itself throws on a miss, matching the
                // engine's own CHILD_ASSET_CODEC contract.
                if (InteractionManager.walkInteraction(collector, context, ref.tag(), ref.id())) {
                    return;
                }
            }
        }
    }

    /**
     * The guarding {@link Collector}: mirrors {@code into}/{@code outof} into an identity-based
     * ancestor path (root's null frame handled as a non-pushing frame), records a {@link ChainNode}
     * per {@code collect}, and stops the walk (returns {@code true}) on a cycle, an exceeded depth,
     * or an exhausted node budget. Also records the collected interactions in walk order plus a
     * {@code stopped} latch, feeding {@link #walkEngineGapSlots}' worklist.
     */
    private static final class GuardingCollector implements Collector {

        @Nonnull
        private final ChainWalk.Builder builder;
        private final int maxDepth;
        private final int maxNodes;

        /** Mirrors every {@code into}/{@code outof} pair, including the root's null frame. */
        @Nonnull
        private final Deque<Boolean> frames = new ArrayDeque<>();
        /** Identity-based ancestor path of real (non-null) interactions currently being walked. */
        @Nonnull
        private final List<Interaction> path = new ArrayList<>();
        /** Every collected interaction in walk order - {@link #walkEngineGapSlots}' worklist. */
        @Nonnull
        private final List<Interaction> collectedOrder = new ArrayList<>();
        /** Latched when any {@code collect} stopped the walk (cycle / depth / node budget). */
        private boolean stopped;
        private int nodeCount;

        private GuardingCollector(@Nonnull ChainWalk.Builder builder, int maxDepth, int maxNodes) {
            this.builder = builder;
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
        }

        @Override
        public void start() {
            // No state to reset: one GuardingCollector instance backs exactly one walk.
        }

        @Override
        public void into(@Nonnull InteractionContext context, @Nullable Interaction interaction) {
            if (interaction != null) {
                path.add(interaction);
                frames.push(Boolean.TRUE);
            } else {
                frames.push(Boolean.FALSE);
            }
        }

        @Override
        public boolean collect(@Nonnull CollectorTag tag, @Nonnull InteractionContext context,
                               @Nonnull Interaction interaction) {
            int depth = path.size();
            boolean cycle = isOnPath(interaction);

            ChainNode node = ChainNode.of(interaction, interaction.getId(), depth, tagLabel(tag));
            builder.node(node);
            collectedOrder.add(interaction);
            nodeCount++;

            if (cycle) {
                builder.cycleAt(node);
                stopped = true;
                return true;
            }
            if (depth > maxDepth) {
                builder.depthExceeded(true);
                stopped = true;
                return true;
            }
            if (nodeCount >= maxNodes) {
                builder.nodeLimitExceeded(true);
                stopped = true;
                return true;
            }
            return false;
        }

        @Override
        public void outof() {
            Boolean pushedReal = frames.isEmpty() ? Boolean.FALSE : frames.pop();
            if (Boolean.TRUE.equals(pushedReal) && !path.isEmpty()) {
                path.remove(path.size() - 1);
            }
        }

        @Override
        public void finished() {
            // Nothing further to reconcile: a stop is already reflected on `builder` by whichever
            // collect() call returned true, and a normal completion leaves every flag false.
        }

        private boolean isOnPath(@Nonnull Interaction interaction) {
            for (Interaction ancestor : path) {
                if (ancestor == interaction) {
                    return true;
                }
            }
            return false;
        }
    }

    @Nonnull
    private static String tagLabel(@Nullable CollectorTag tag) {
        if (tag == null || tag == CollectorTag.ROOT) {
            return "ROOT";
        }
        if (tag instanceof StringTag stringTag) {
            String value = stringTag.getTag();
            return value != null ? value : "ROOT";
        }
        return tag.toString();
    }
}
