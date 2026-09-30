package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.EntityPart;
import com.hypixel.hytale.protocol.ModelParticle;

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

    @Test
    void spawnAt_fullArity_nullAssetId_isANoOp() {
        assertFalse(ModelParticleService.spawnAt(null, null, new Vector3d(0, 0, 0), 0f, 0f, 0f, 1f,
                new Color((byte) 255, (byte) 0, (byte) 0), 4f));
    }

    @Test
    void spawnOn_nullRefOrNoParticles_answersNoViewers() {
        assertEquals(0, ModelParticleService.spawnOn(null, null, List.of(
                ModelParticleService.AttachedParticle.of("Some_System"))));
        assertEquals(0, ModelParticleService.spawnOn(null, null, List.of()));
        assertEquals(0, ModelParticleService.spawnOn(null, null, null));
    }

    @Test
    void aTintReadsASixDigitHexAndNothingElse() {
        Color tint = ModelParticleService.tint(" #8FD36A ");
        assertEquals((byte) 0x8f, tint.red);
        assertEquals((byte) 0xd3, tint.green);
        assertEquals((byte) 0x6a, tint.blue);
        assertEquals(new Color((byte) 255, (byte) 0, (byte) 0), ModelParticleService.tint("ff0000"),
                "the hash is optional");
        assertNull(ModelParticleService.tint(null));
        assertNull(ModelParticleService.tint("   "));
        assertNull(ModelParticleService.tint("#fff"));
        assertNull(ModelParticleService.tint("#ff0000aa"), "no alpha: a tint is three channels");
        assertNull(ModelParticleService.tint("#gg0000"));
    }

    @Test
    void anAttachedParticleMapsOntoTheProtocolLeafFieldForField() {
        Color tint = new Color((byte) 10, (byte) 200, (byte) 30);
        Direction turn = new Direction(1f, 2f, 3f);
        ModelParticleService.AttachedParticle spec = ModelParticleService.AttachedParticle.of("Block_Gem_Sparks")
                .withScale(0.5f).withColor(tint).at(EntityPart.Entity, "Hand_R")
                .withOffsets(new Vector3f(0f, 0.8f, 0f), turn);
        ModelParticle leaf = spec.toProtocol();
        assertEquals("Block_Gem_Sparks", leaf.systemId);
        assertEquals(0.5f, leaf.scale);
        assertEquals(tint, leaf.color);
        assertEquals(EntityPart.Entity, leaf.targetEntityPart);
        assertEquals("Hand_R", leaf.targetNodeName);
        assertEquals(new Vector3f(0f, 0.8f, 0f), leaf.positionOffset);
        assertEquals(turn, leaf.rotationOffset);
        assertFalse(leaf.detachedFromModel);
        assertTrue(leaf.clearParticlesOnRemove, "a default-tuned system is cleared with its entity");

        ModelParticle plain = ModelParticleService.AttachedParticle.of("Seeds_Eternal").toProtocol();
        assertEquals(EntityPart.Self, plain.targetEntityPart, "the default rides the whole entity");
        assertEquals(1f, plain.scale);
        assertNull(plain.color);
        assertNull(plain.targetNodeName);
    }
}
