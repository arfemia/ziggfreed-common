package com.ziggfreed.common.npc.placement.registry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;

/**
 * One veto in the chain that decides whether a placement may stand in a given world.
 *
 * <p><b>The decision takes a CONTEXT, not just a placement.</b> "This NPC is disabled" and "this
 * NPC is disabled in the temple" are different statements, and only the second is expressible if
 * the gate can see the world. It is also what lets a diagnostic listing say WHY a placement is
 * absent: every deny carries a reason key, so the answer is a line of text rather than a shrug.
 *
 * <p>Any deny wins; see {@link PlacementGates} for the chain.
 */
@FunctionalInterface
public interface PlacementGate {

    /**
     * What a gate is given. Field-additive: a new component never breaks an implementer.
     *
     * @param placement   the folded placement being considered
     * @param world       the world it would stand in
     * @param store       the live entity store, when the caller has one
     */
    record GateContext(@Nonnull NpcPlacementAsset placement,
                       @Nullable World world,
                       @Nullable Store<EntityStore> store) {

        /** The placement id, lower-cased (the id every override and ledger row is keyed by). */
        @Nonnull
        public String placementId() {
            String id = placement.getId();
            return id == null ? "" : id;
        }
    }

    /** A gate's answer: allow, or deny with a reason key a listing can show. */
    record GateVerdict(boolean allowed, @Nullable String reasonKey) {

        private static final GateVerdict ALLOW = new GateVerdict(true, null);

        /** The shared allow verdict (no reason needed). */
        @Nonnull
        public static GateVerdict allow() {
            return ALLOW;
        }

        /** Deny with a stable reason key, e.g. {@code "placement.denied.disabled"}. */
        @Nonnull
        public static GateVerdict deny(@Nonnull String reasonKey) {
            return new GateVerdict(false, reasonKey);
        }

        public boolean isDenied() {
            return !allowed;
        }
    }

    /** Decide for one placement in one world. Must not throw (the chain guards anyway). */
    @Nonnull
    GateVerdict decide(@Nonnull GateContext ctx);
}
