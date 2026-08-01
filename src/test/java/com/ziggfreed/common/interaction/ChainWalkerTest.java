package com.ziggfreed.common.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.InteractionType;
import org.junit.jupiter.api.Test;

/**
 * The slice of {@link ChainWalker} testable without a live Hytale server: a null/blank root id or
 * a null {@code InteractionType} short-circuits BEFORE any engine touch (the
 * {@code RootInteraction} asset store is unreachable outside a bootstrapped server), so those
 * degrade to {@code rootResolved() == false} the same way an unresolved id does live. The full
 * walk (a live {@code RootInteraction} store, {@code InteractionManager.walkChain}, cycle/depth/
 * node-cap enforcement, aborted-chain reporting) needs a running server and is smoke-tested in the
 * consuming mods.
 */
class ChainWalkerTest {

    @Test
    void walk_nullRootId_isRootUnresolvedWithNoEngineTouch() {
        ChainWalk walk = ChainWalker.walk(null, InteractionType.Ability1);

        assertFalse(walk.rootResolved());
        assertTrue(walk.nodes().isEmpty());
        assertFalse(walk.aborted());
    }

    @Test
    void walk_blankRootId_isRootUnresolved() {
        ChainWalk walk = ChainWalker.walk("", InteractionType.Ability1);

        assertFalse(walk.rootResolved());
    }

    @Test
    void walk_blankRootId_preservesRootId() {
        ChainWalk walk = ChainWalker.walk("   ", InteractionType.Ability1);

        assertEquals("   ", walk.rootId());
    }

    @Test
    void walk_nullType_isRootUnresolved() {
        ChainWalk walk = ChainWalker.walk("Some_Id", null);

        assertFalse(walk.rootResolved());
    }

    @Test
    void walk_nullRootIdAndNullType_isRootUnresolved() {
        ChainWalk walk = ChainWalker.walk(null, null);

        assertFalse(walk.rootResolved());
    }

    @Test
    void defaultMaxDepth_is32() {
        assertEquals(32, ChainWalker.DEFAULT_MAX_DEPTH);
    }

    @Test
    void defaultMaxNodes_is512() {
        assertEquals(512, ChainWalker.DEFAULT_MAX_NODES);
    }

    @Test
    void walk_explicitCaps_nullRootId_stillShortCircuits() {
        ChainWalk walk = ChainWalker.walk(null, InteractionType.Ability1, 8, 16);

        assertFalse(walk.rootResolved());
    }

    @Test
    void walk_explicitCapsAndNullContext_nullRootId_stillShortCircuits() {
        ChainWalk walk = ChainWalker.walk(null, InteractionType.Ability1, 8, 16, null);

        assertFalse(walk.rootResolved());
    }
}
