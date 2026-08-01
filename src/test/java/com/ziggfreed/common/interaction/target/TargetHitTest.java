package com.ziggfreed.common.interaction.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

class TargetHitTest {

    @Test
    void accessorsRoundTrip() {
        Ref<EntityStore> ref = null;
        Vector3d pos = new Vector3d(1, 2, 3);
        TargetHit hit = TargetHit.of(ref, pos, 7.5, 33.0);

        assertNull(hit.ref());
        assertEquals(new Vector3d(1, 2, 3), hit.position());
        assertEquals(7.5, hit.distance(), 1e-9);
        assertEquals(33.0, hit.angleDegrees(), 1e-9);
    }

    @Test
    void positionIsDefensivelyCopiedAtConstructionAndOnEveryRead() {
        Vector3d source = new Vector3d(1, 1, 1);
        TargetHit hit = TargetHit.of(null, source, 1.0, 0.0);

        // Mutating the source vector after construction never reaches the hit.
        source.set(9, 9, 9);
        assertEquals(new Vector3d(1, 1, 1), hit.position());

        // Mutating one read's return value never reaches the next read.
        Vector3d first = hit.position();
        first.set(5, 5, 5);
        assertEquals(new Vector3d(1, 1, 1), hit.position());
    }

    @Test
    void toStringNeverThrows() {
        TargetHit hit = TargetHit.of(null, new Vector3d(), 0.0, 0.0);
        assertNotNull(hit.toString());
    }
}
