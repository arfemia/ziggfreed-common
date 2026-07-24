package com.ziggfreed.common.entity.performer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * The orphan-reconcile sweep over {@link PerformerIdentityComponent} - the public helper a consumer
 * runs at boot / engage to close the persistence gap a persistent performer opens. It queries every
 * performer entity natively, asks a caller-supplied {@link ReconcilePolicy} what to do with each
 * (from its pure {@link PerformerIdentity}), and DESPAWNS the ones the policy rejects (or hands back
 * the ones it wants RE-BOUND). The DECISION is pure and unit-testable (the policy factories below);
 * only the ECS query + despawn wiring needs a live store.
 *
 * <p><b>Boot</b> ({@link #bootDespawnAll}): no session survives a server restart, so despawn every
 * performer - a persisted performer whose session died with the server would otherwise strand
 * forever. <b>Engage</b> ({@link #engageStale}): despawn any pre-existing performer at the station
 * being engaged whose owner is not the engaging player (a crash-orphaned double at that block).
 */
public final class PerformerReconciler {

    private PerformerReconciler() {
    }

    /** What a {@link ReconcilePolicy} decides for one performer. */
    public enum ReconcileDecision {
        /** Leave the performer alone. */
        KEEP,
        /** Despawn the performer (a stranded orphan). */
        DESPAWN,
        /** Keep it but hand it back to the caller to re-bind to a live session. */
        REBIND
    }

    /** A pure decision over a performer's {@link PerformerIdentity}. */
    @FunctionalInterface
    public interface ReconcilePolicy {
        @Nonnull
        ReconcileDecision decide(@Nonnull PerformerIdentity id);
    }

    /** A performer the sweep matched: its entity ref + decoded identity (for a REBIND hand-back). */
    public record PerformerBinding(@Nonnull Ref<EntityStore> ref, @Nonnull PerformerIdentity identity) {
    }

    /** The outcome of one {@link #sweep}: counts + any REBIND performers for the caller to process. */
    public record ReconcileSummary(int scanned, int despawned, @Nonnull List<PerformerBinding> rebinds) {
    }

    // ==================== policy factories (pure, unit-testable) ====================

    /** Despawn EVERY performer (the boot policy - no session exists across a restart). */
    @Nonnull
    public static ReconcilePolicy bootDespawnAll() {
        return id -> ReconcileDecision.DESPAWN;
    }

    /**
     * Despawn a performer whose owner is not live per {@code isLive}; keep the rest. A null owner
     * (unparseable) counts as not-live -&gt; despawn.
     */
    @Nonnull
    public static ReconcilePolicy ownerNotLive(@Nonnull Predicate<UUID> isLive) {
        return id -> {
            UUID owner = id.ownerUuid();
            return owner != null && isLive.test(owner) ? ReconcileDecision.KEEP : ReconcileDecision.DESPAWN;
        };
    }

    /**
     * Despawn a performer AT the engaging station block whose owner is not the engaging player
     * (a stale double from a crashed prior session); keep everything at other stations. Pass a
     * {@code null} {@code engagingOwner} to despawn ANY performer at that station.
     */
    @Nonnull
    public static ReconcilePolicy engageStale(@Nullable UUID engagingOwner, @Nonnull String engagingStationKey) {
        return id -> {
            if (engagingStationKey.equals(id.stationKey()) && !Objects.equals(engagingOwner, id.ownerUuid())) {
                return ReconcileDecision.DESPAWN;
            }
            return ReconcileDecision.KEEP;
        };
    }

    // ==================== the live sweep (world thread) ====================

    /**
     * Sweep every {@link PerformerIdentityComponent} entity in {@code store}, applying {@code policy}
     * to each: DESPAWN removes it (via the parallel iteration's command buffer, the
     * {@code NPCCleanCommand} first-party precedent), REBIND collects it for the caller,  KEEP does
     * nothing. Returns a {@link ReconcileSummary}. WORLD-THREAD ONLY; never throws (a failure logs
     * and returns whatever was tallied). An empty {@link ReconcileSummary} when the component is not
     * registered ({@link PerformerIdentityComponent#TYPE} null).
     */
    @Nonnull
    public static ReconcileSummary sweep(@Nonnull Store<EntityStore> store, @Nonnull ReconcilePolicy policy) {
        ComponentType<EntityStore, PerformerIdentityComponent> type = PerformerIdentityComponent.getComponentType();
        if (type == null) {
            return new ReconcileSummary(0, 0, List.of());
        }
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger despawned = new AtomicInteger();
        ConcurrentLinkedQueue<PerformerBinding> rebinds = new ConcurrentLinkedQueue<>();
        try {
            store.forEachEntityParallel(type, (index, chunk, cmdBuffer) -> {
                try {
                    PerformerIdentityComponent comp = chunk.getComponent(index, type);
                    if (comp == null) {
                        return;
                    }
                    scanned.incrementAndGet();
                    PerformerIdentity identity = comp.toIdentity();
                    ReconcileDecision decision = policy.decide(identity);
                    if (decision == ReconcileDecision.DESPAWN) {
                        cmdBuffer.tryRemoveEntity(chunk.getReferenceTo(index), RemoveReason.REMOVE);
                        despawned.incrementAndGet();
                    } else if (decision == ReconcileDecision.REBIND) {
                        rebinds.add(new PerformerBinding(chunk.getReferenceTo(index), identity));
                    }
                } catch (Throwable perEntity) {
                    fine("sweep per-entity failed: " + perEntity.getMessage());
                }
            });
        } catch (Throwable t) {
            warn("sweep failed: " + t.getMessage(), t);
        }
        return new ReconcileSummary(scanned.get(), despawned.get(), new ArrayList<>(rebinds));
    }

    // ==================== logging ====================

    private static void warn(@Nonnull String message, @Nullable Throwable cause) {
        try {
            if (cause != null) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().withCause(cause)
                        .log("[ziggfreed-common][performer] " + message);
            } else {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][performer] " + message);
            }
        } catch (Throwable ignored) {
            // log-manager-less unit JVM.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][performer] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM.
        }
    }
}
