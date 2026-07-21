package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The one slice of {@link ModelParticleService} testable without a live Hytale server: the
 * null-asset-id no-op contract on BOTH the uncapped 3-arg overload and the new duration-capped
 * 4-arg overload (added to fix a station completion-flourish particle leak - see
 * {@code com.ziggfreed.mmoskilltree.station.StationService#emitMoment} in the consuming MMO
 * jar). A null {@code particleAsset} returns {@code false} BEFORE the engine {@code Store} is
 * ever touched, so {@code null} is a safe stand-in for a live store here; every other branch
 * calls the real engine {@code ParticleUtil} and needs a running server (smoke-tested there).
 */
class ModelParticleServiceTest {

    @Test
    void spawnAt_uncapped_nullAssetId_isANoOp() {
        assertFalse(ModelParticleService.spawnAt(null, null, new Vector3d(0, 0, 0)));
    }

    @Test
    void spawnAt_capped_nullAssetId_isANoOp() {
        assertFalse(ModelParticleService.spawnAt(null, null, new Vector3d(0, 0, 0), 4.0f));
    }

    @Test
    void spawnAt_capped_nullAssetId_isANoOpRegardlessOfDuration() {
        // The null-id guard fires before maxDurationSeconds is ever consulted - zero, negative,
        // and a normal positive cap all take the same early-return path.
        assertFalse(ModelParticleService.spawnAt(null, null, new Vector3d(0, 0, 0), 0f));
        assertFalse(ModelParticleService.spawnAt(null, null, new Vector3d(0, 0, 0), -1f));
    }
}
