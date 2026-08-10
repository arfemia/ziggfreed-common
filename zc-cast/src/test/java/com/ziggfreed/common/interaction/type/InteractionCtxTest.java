package com.ziggfreed.common.interaction.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Every {@link InteractionCtx} accessor must degrade to {@code null} on a null context (and, for
 * the position-family accessors, a null ref) rather than throw - no test here may construct a
 * live {@code InteractionContext} (unreachable from a unit JVM).
 */
class InteractionCtxTest {

    @Test
    void everyAccessorDegradesToNullOnNullContext() {
        assertNull(InteractionCtx.buffer(null));
        assertNull(InteractionCtx.firingEntity(null));
        assertNull(InteractionCtx.owner(null));
        assertNull(InteractionCtx.target(null));
        assertNull(InteractionCtx.heldItem(null));
        assertNull(InteractionCtx.world(null));
        assertNull(InteractionCtx.player(null, null));
        assertNull(InteractionCtx.entityId(null, null));
        assertNull(InteractionCtx.position(null, null));
        assertNull(InteractionCtx.eyePosition(null, null, InteractionCtx.DEFAULT_EYE_HEIGHT));
        assertNull(InteractionCtx.lookDirection(null, null));
    }

    @Test
    void positionFamilyDegradesToNullOnNullRefEvenWithoutContext() {
        assertNull(InteractionCtx.position(null, null));
        assertNull(InteractionCtx.eyePosition(null, null, 1.0));
        assertNull(InteractionCtx.lookDirection(null, null));
    }

    @Test
    void defaultEyeHeightIsPinned() {
        assertEquals(1.6, InteractionCtx.DEFAULT_EYE_HEIGHT);
    }
}
