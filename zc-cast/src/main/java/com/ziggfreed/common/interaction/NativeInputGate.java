package com.ziggfreed.common.interaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.DurabilityOperator;
import com.hypixel.hytale.protocol.ValueType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Answers "could this native interaction root actually do something for this entity right now?" by
 * reading the CONDITION PREFIX the root's own author wrote, without running it.
 *
 * <p>A mod that puts its own interaction on a key an item already uses has to decide which of the
 * two a press belongs to, and the honest answer depends on the item's own state: a weapon whose
 * signature move costs a full energy bar has no claim on the key while the bar is empty, and a
 * reload has none while the magazine is full. Hytale writes exactly that as the opening nodes of
 * the root's chain - a {@code StatsCondition} over the entity's {@link EntityStatMap}, a {@code
 * DurabilityCondition} over the held stack - and, when the condition fails, simply stops. Nothing
 * observes that stop from outside: none of the shipped weapon roots author a {@code Failed} branch
 * on their gate, and {@code RunRootInteraction} finishes and replaces the chain rather than
 * reporting back. So the only place a press can be routed correctly is BEFORE dispatch, which is
 * what this is for.
 *
 * <p><b>Mechanism.</b> The walk starts at the root's first node and follows {@code Next} for as
 * long as the nodes are conditions, stopping at the first node that does something (the payload).
 * A {@code Replace} slot is followed transparently, through the held item's own {@code
 * InteractionVars} when it names one and through the authored default otherwise, because a weapon
 * may put its gate behind one. Values come from the engine's own {@link Interaction#toPacket()}
 * (public, and cached behind a soft reference, so the walk is cheap enough for a key press), and
 * each condition is then evaluated with the same arithmetic the engine's own {@code canAfford} and
 * {@code matches} use, against live state. Nothing here mutates anything.
 *
 * <p><b>The verdict separates two different kinds of no</b>, because a caller routing a key press
 * wants them apart. {@link Verdict#satisfied()} is whether every condition the walk could read
 * passes. {@link Verdict#hasResourceGate()} is whether any of them was a stat cost at all: a chain
 * gated only on durability, or gated on nothing, spends no resource and so never means "a charged
 * move is ready and waiting" - it is exactly as ready on the thousandth press as on the first. A
 * caller that wants the item to win the key only when it has something banked up asks {@link
 * Verdict#readyToSpend()}, which requires both.
 *
 * <p><b>Every uncertainty resolves to not-ready.</b> An unresolvable id, a condition shape whose
 * values this cannot read, an engine throw: all end the walk unsatisfied rather than guessing in
 * the item's favour. The intended pairing is a caller that fires the root anyway when its own
 * alternative declines, which makes a wrong not-ready cost nothing - the chain then runs and
 * re-evaluates the very same conditions itself, authoritatively.
 *
 * <p><b>World-thread only</b> (reads live components); the caller guarantees the thread.
 */
public final class NativeInputGate {

    /** Depth cap on the condition-prefix walk. Content nests far below this; a cycle would not. */
    private static final int MAX_PREFIX_NODES = 32;

    /**
     * What a root's condition prefix says about the entity's ability to run it now.
     *
     * @param hasResourceGate at least one stat cost was read in the prefix - the root spends
     *                        something, so being able to run it is a state that comes and goes.
     * @param satisfied       every condition the walk could read passes right now. {@code false}
     *                        also covers every case the walk could not read (see the class doc).
     */
    public record Verdict(boolean hasResourceGate, boolean satisfied) {

        /** No root to probe, or nothing readable in it: spends nothing, claims nothing. */
        public static final Verdict NONE = new Verdict(false, false);

        /**
         * The root has a stat cost AND the entity can pay it - the one state that means this item
         * has a move banked up right now. A durability-only or condition-free chain is never this.
         */
        public boolean readyToSpend() {
            return hasResourceGate && satisfied;
        }
    }

    private NativeInputGate() {
    }

    /**
     * Read {@code rootInteractionId}'s condition prefix against {@code ref}'s live stats and the
     * held stack's durability.
     *
     * @param heldItem  the held item type, used to resolve a {@code Replace} slot against its own
     *                  {@code InteractionVars}; {@code null} falls back to each slot's default.
     * @param heldStack the held stack, for durability conditions; {@code null} fails them.
     * @return never {@code null}; {@link Verdict#NONE} for anything unreadable.
     */
    @Nonnull
    public static Verdict probe(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                                @Nullable Item heldItem, @Nullable ItemStack heldStack,
                                @Nullable String rootInteractionId) {
        if (store == null || ref == null || !ref.isValid()
                || rootInteractionId == null || rootInteractionId.isBlank()) {
            return Verdict.NONE;
        }
        try {
            EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
            boolean hasResourceGate = false;
            String nodeId = firstNodeOf(rootInteractionId);

            for (int step = 0; step < MAX_PREFIX_NODES; step++) {
                if (nodeId == null) {
                    // Ran off the end of the chain without meeting a payload: everything the author
                    // gated on passed.
                    return new Verdict(hasResourceGate, true);
                }
                Interaction node = Interaction.getAssetMap().getAsset(nodeId);
                if (node == null) {
                    return Verdict.NONE;
                }
                com.hypixel.hytale.protocol.Interaction packet = node.toPacket();

                if (packet instanceof com.hypixel.hytale.protocol.ReplaceInteraction replace) {
                    String slotRoot = replacementRoot(heldItem, replace);
                    if (slotRoot == null) {
                        return Verdict.NONE;
                    }
                    nodeId = firstNodeOf(slotRoot);
                    continue;
                }
                if (packet instanceof com.hypixel.hytale.protocol.StatsConditionInteraction cost) {
                    if (cost.costs == null) {
                        // The engine reads a missing cost map as unaffordable, so the chain stops
                        // here whatever else it was going to do.
                        return new Verdict(hasResourceGate, false);
                    }
                    if (cost.costs.isEmpty()) {
                        // Names no stat: passes for the engine, and spends nothing, so it is not
                        // what makes an item ready.
                        nodeId = nextOf(cost.next);
                        continue;
                    }
                    hasResourceGate = true;
                    if (!statsSatisfied(stats, cost)) {
                        return new Verdict(true, false);
                    }
                    nodeId = nextOf(cost.next);
                    continue;
                }
                if (packet instanceof com.hypixel.hytale.protocol.DurabilityConditionInteraction wear) {
                    if (!durabilitySatisfied(heldStack, wear)) {
                        return new Verdict(hasResourceGate, false);
                    }
                    nodeId = nextOf(wear.next);
                    continue;
                }
                // The payload, or a condition shape this cannot read. Either way the prefix ends
                // here, and what was read up to this point is the whole answer.
                return new Verdict(hasResourceGate, true);
            }
            return Verdict.NONE;
        } catch (Throwable t) {
            SafeLog.warn("NativeInputGate.probe(" + rootInteractionId + ") failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return Verdict.NONE;
        }
    }

    /** The first interaction id of a root, or {@code null} when the root is missing or empty. */
    @Nullable
    private static String firstNodeOf(@Nonnull String rootInteractionId) {
        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootInteractionId);
        if (root == null) {
            return null;
        }
        String[] ids = root.getInteractionIds();
        return ids != null && ids.length > 0 ? ids[0] : null;
    }

    /** An interaction id from a packet's index slot, or {@code null} when the slot is empty. */
    @Nullable
    private static String nextOf(int index) {
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            return null;
        }
        Interaction next = Interaction.getAssetMap().getAsset(index);
        return next != null ? next.getId() : null;
    }

    /**
     * The root a {@code Replace} slot resolves to: the held item's own {@code InteractionVars}
     * entry when it names one, the authored default otherwise.
     */
    @Nullable
    private static String replacementRoot(@Nullable Item heldItem,
                                          @Nonnull com.hypixel.hytale.protocol.ReplaceInteraction replace) {
        if (heldItem != null && replace.variable != null) {
            String authored = heldItem.getInteractionVars().get(replace.variable);
            if (authored != null) {
                return authored;
            }
        }
        if (replace.defaultValue == AssetMapWithIndexes.NOT_FOUND) {
            return null;
        }
        RootInteraction fallback = RootInteraction.getAssetMap().getAsset(replace.defaultValue);
        return fallback != null ? fallback.getId() : null;
    }

    /**
     * The engine's own cost arithmetic: each cost is read against the stat's absolute value or its
     * percentage, and {@code LessThan} inverts the comparison so a "there is room for more" gate (a
     * magazine short of full) reads correctly. {@code Lenient} lets a stat with an overdraw pool go
     * negative, exactly as the engine does.
     */
    private static boolean statsSatisfied(@Nullable EntityStatMap stats,
                                          @Nonnull com.hypixel.hytale.protocol.StatsConditionInteraction cost) {
        if (stats == null || cost.costs == null || cost.costs.isEmpty()) {
            return false;
        }
        for (var entry : cost.costs.entrySet()) {
            var stat = stats.get(entry.getKey().intValue());
            if (stat == null) {
                return false;
            }
            float value = cost.valueType == ValueType.Absolute ? stat.get() : stat.asPercentage() * 100f;
            float required = entry.getValue();
            if (cost.lessThan) {
                if (value >= required) {
                    return false;
                }
            } else if (value < required && !(cost.lenient && value > 0 && stat.getMin() < 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The engine's own wear comparison: an unbreakable stack always passes, an absent one never
     * does.
     */
    private static boolean durabilitySatisfied(@Nullable ItemStack heldStack,
                                               @Nonnull com.hypixel.hytale.protocol.DurabilityConditionInteraction wear) {
        if (heldStack == null) {
            return false;
        }
        if (heldStack.isUnbreakable()) {
            return true;
        }
        double max = heldStack.getMaxDurability();
        double raw = heldStack.getDurability();
        double value = wear.valueType == ValueType.Absolute ? raw : (max > 0 ? (raw / max) * 100.0 : 0.0);
        DurabilityOperator operator = wear.operator;
        return switch (operator) {
            case LessThan -> value < wear.threshold;
            case LessOrEqual -> value <= wear.threshold;
            case GreaterThan -> value > wear.threshold;
            case GreaterOrEqual -> value >= wear.threshold;
            case Equal -> value == wear.threshold;
            case NotEqual -> value != wear.threshold;
        };
    }
}
