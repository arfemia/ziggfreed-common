package com.ziggfreed.common.entity.performer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.entity.performer.PerformerReconciler.ReconcileDecision;
import com.ziggfreed.common.entity.performer.PerformerReconciler.ReconcilePolicy;

/**
 * The PURE reconcile decision core - the {@link PerformerReconciler} policy factories, evaluated
 * over fixture {@link PerformerIdentity} values with no live store (the ECS sweep wiring is
 * engine-only and untested, matching the package's test split).
 */
class PerformerReconcileTest {

    private static PerformerIdentity id(UUID owner, String station, PerformerKind kind) {
        return new PerformerIdentity(owner, station, kind, 0L);
    }

    // ==================== bootDespawnAll ====================

    @Test
    void bootDespawnAll_alwaysDespawns() {
        ReconcilePolicy p = PerformerReconciler.bootDespawnAll();
        assertEquals(ReconcileDecision.DESPAWN, p.decide(id(UUID.randomUUID(), "w:1:1:1", PerformerKind.HOLDER)));
        assertEquals(ReconcileDecision.DESPAWN, p.decide(id(null, "w:2:2:2", PerformerKind.NPC_ROLE)));
    }

    // ==================== ownerNotLive ====================

    @Test
    void ownerNotLive_keepsLiveOwner_despawnsDeadOwner() {
        UUID live = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        Set<UUID> liveSet = Set.of(live);
        ReconcilePolicy p = PerformerReconciler.ownerNotLive(liveSet::contains);
        assertEquals(ReconcileDecision.KEEP, p.decide(id(live, "w:1:1:1", PerformerKind.HOLDER)));
        assertEquals(ReconcileDecision.DESPAWN, p.decide(id(dead, "w:1:1:1", PerformerKind.HOLDER)));
    }

    @Test
    void ownerNotLive_nullOwnerIsNotLive() {
        ReconcilePolicy p = PerformerReconciler.ownerNotLive(u -> true);
        assertEquals(ReconcileDecision.DESPAWN, p.decide(id(null, "w:1:1:1", PerformerKind.HOLDER)),
                "a null (unparseable) owner is treated as not-live");
    }

    // ==================== engageStale ====================

    @Test
    void engageStale_despawnsOtherOwnerAtSameStation() {
        UUID engaging = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ReconcilePolicy p = PerformerReconciler.engageStale(engaging, "w:5:5:5");
        assertEquals(ReconcileDecision.DESPAWN, p.decide(id(other, "w:5:5:5", PerformerKind.HOLDER)),
                "a stale double at the engaged station owned by someone else is despawned");
    }

    @Test
    void engageStale_keepsSameOwnerAtSameStation() {
        UUID engaging = UUID.randomUUID();
        ReconcilePolicy p = PerformerReconciler.engageStale(engaging, "w:5:5:5");
        assertEquals(ReconcileDecision.KEEP, p.decide(id(engaging, "w:5:5:5", PerformerKind.HOLDER)),
                "the engaging player's own performer at that station is kept");
    }

    @Test
    void engageStale_keepsPerformersAtOtherStations() {
        UUID engaging = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ReconcilePolicy p = PerformerReconciler.engageStale(engaging, "w:5:5:5");
        assertEquals(ReconcileDecision.KEEP, p.decide(id(other, "w:9:9:9", PerformerKind.HOLDER)),
                "a performer at a different station is untouched");
    }

    @Test
    void engageStale_nullEngagingOwnerDespawnsAnyoneAtStation() {
        ReconcilePolicy p = PerformerReconciler.engageStale(null, "w:5:5:5");
        assertEquals(ReconcileDecision.DESPAWN, p.decide(id(UUID.randomUUID(), "w:5:5:5", PerformerKind.HOLDER)));
        assertEquals(ReconcileDecision.KEEP, p.decide(id(UUID.randomUUID(), "w:1:1:1", PerformerKind.HOLDER)));
    }
}
