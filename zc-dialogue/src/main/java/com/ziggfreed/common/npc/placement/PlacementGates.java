package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.placement.PlacementGate.GateContext;
import com.ziggfreed.common.npc.placement.PlacementGate.GateVerdict;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ordered chain of {@link PlacementGate}s, and the one place "may this stand here" is
 * answered.
 *
 * <p><b>Any deny wins</b>, and the FIRST deny is the one reported, so a diagnostic listing names
 * the most fundamental reason rather than the last one checked. Order therefore matters, and the
 * two built-ins run first: the asset's own {@code Enabled} leaf, then the server owner's override
 * file. A consumer's veto (and the authored {@code Requires} evaluation) runs after them.
 *
 * <p><b>A deny DESPAWNS what is already standing.</b> The gate is not consulted only at placement
 * time: the reconciler asks it about every resident placed NPC too, so disabling one takes effect
 * on the next sweep instead of at the next restart. That is the whole point of an admin kill
 * switch, and it is why {@link NpcPlacementOverrides#setEnabled} forces a sweep after writing.
 *
 * <p>Registration is JVM-global and additive; a guarded gate that throws is treated as ALLOW
 * (a broken third-party veto must not be able to wipe every NPC on the server).
 */
public final class PlacementGates {

    /** Reason key: the asset itself is authored off. */
    public static final String REASON_DISABLED = "placement.denied.disabled";
    /** Reason key: the server owner switched this placement (or everything) off. */
    public static final String REASON_OWNER_OVERRIDE = "placement.denied.owner_override";
    /** Reason key: an authored {@code Requires} condition did not pass. */
    public static final String REASON_REQUIRES = "placement.denied.requires";

    private static final CopyOnWriteArrayList<PlacementGate> GATES = new CopyOnWriteArrayList<>();

    static {
        // 1. The asset's own switch: the content author's permanent off.
        GATES.add(ctx -> ctx.placement().isEnabled()
                ? GateVerdict.allow()
                : GateVerdict.deny(REASON_DISABLED));
        // 2. The server owner's override file: the admin's off, which outranks nothing but the
        //    author's own and is checked before any consumer opinion.
        GATES.add(ctx -> NpcPlacementOverrides.getInstance().isEnabled(ctx.placementId())
                ? GateVerdict.allow()
                : GateVerdict.deny(REASON_OWNER_OVERRIDE));
        // 3. The authored Requires block, resolved through the open factor vocabulary. It sits in
        //    the chain rather than beside it so one call answers "may this stand here" completely.
        GATES.add(ctx -> {
            String failed = PlacementFactorRegistry.firstFailure(
                    ctx.placement().getRequires(), ctx.placementId(), ctx.world(), ctx.store());
            return failed == null ? GateVerdict.allow() : GateVerdict.deny(REASON_REQUIRES);
        });
    }

    private PlacementGates() {
    }

    /**
     * Append a consumer veto to the chain. Runs AFTER the three built-ins, so an owner's off
     * switch is always reported in preference to a domain reason.
     */
    public static void register(@Nullable PlacementGate gate) {
        if (gate != null) {
            GATES.add(gate);
        }
    }

    /** How many gates are in the chain (diagnostics, tests). */
    public static int size() {
        return GATES.size();
    }

    /** Decide for {@code placement} in {@code world}. Never throws. */
    @Nonnull
    public static GateVerdict decide(@Nonnull NpcPlacementAsset placement, @Nullable World world,
            @Nullable Store<EntityStore> store) {
        return decide(new GateContext(placement, world, store));
    }

    /** Decide for a pre-built context (the pure entry point tests drive). */
    @Nonnull
    public static GateVerdict decide(@Nonnull GateContext ctx) {
        for (PlacementGate gate : GATES) {
            GateVerdict verdict;
            try {
                verdict = gate.decide(ctx);
            } catch (Throwable t) {
                // A broken veto must never be able to despawn the server's NPCs.
                SafeLog.warn("[placement] gate failed for '" + ctx.placementId() + "': " + t.getMessage());
                continue;
            }
            if (verdict != null && verdict.isDenied()) {
                return verdict;
            }
        }
        return GateVerdict.allow();
    }

    /**
     * Run an explicit chain against a context. PURE (it consults no registry), which is what makes
     * precedence and any-deny-wins unit-testable without registering anything globally.
     */
    @Nonnull
    public static GateVerdict decideWith(@Nonnull List<PlacementGate> chain, @Nonnull GateContext ctx) {
        for (PlacementGate gate : chain) {
            GateVerdict verdict;
            try {
                verdict = gate.decide(ctx);
            } catch (Throwable t) {
                continue;
            }
            if (verdict != null && verdict.isDenied()) {
                return verdict;
            }
        }
        return GateVerdict.allow();
    }
}
