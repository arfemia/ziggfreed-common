package com.ziggfreed.common.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import org.junit.jupiter.api.Test;

/**
 * {@link ChainWalk} builder-assembly tests. Every node here is a synthetic {@code
 * ChainNode.of(null, ...)} entry (no real {@code Interaction}), so
 * {@link ChainWalk#containsType(Class)}/{@link ChainWalk#nodesOfType(Class)} are exercised only
 * through their null-safety contract, not a real match.
 */
class ChainWalkTest {

    @Test
    void builder_assemblesEveryField() {
        ChainNode node = ChainNode.of(null, "a", 0, "ROOT");

        ChainWalk walk = ChainWalk.builder("Root_Id")
                .rootResolved(true)
                .node(node)
                .depthExceeded(false)
                .nodeLimitExceeded(false)
                .build();

        assertEquals("Root_Id", walk.rootId());
        assertTrue(walk.rootResolved());
        assertEquals(1, walk.nodes().size());
        assertFalse(walk.cycleDetected());
        assertFalse(walk.depthExceeded());
        assertFalse(walk.nodeLimitExceeded());
        assertFalse(walk.aborted());
        assertNull(walk.abortReason());
    }

    @Test
    void builder_nullRootId_normalizesToEmptyString() {
        ChainWalk walk = ChainWalk.builder(null).build();

        assertEquals("", walk.rootId());
    }

    @Test
    void nodes_isImmutable() {
        ChainWalk walk = ChainWalk.builder("id").node(ChainNode.of(null, "a", 0, "ROOT")).build();

        assertThrows(UnsupportedOperationException.class, () -> walk.nodes().add(ChainNode.of(null, "b", 0, "ROOT")));
    }

    @Test
    void containsType_null_isFalse() {
        ChainWalk walk = ChainWalk.builder("id").node(ChainNode.of(null, "a", 0, "ROOT")).build();

        assertFalse(walk.containsType(null));
    }

    @Test
    void nodesOfType_null_isEmpty() {
        ChainWalk walk = ChainWalk.builder("id").node(ChainNode.of(null, "a", 0, "ROOT")).build();

        assertTrue(walk.nodesOfType(null).isEmpty());
    }

    @Test
    void nodesOfType_skipsNodesWithNullInteraction() {
        ChainWalk walk = ChainWalk.builder("id").node(ChainNode.of(null, "a", 0, "ROOT")).build();

        assertTrue(walk.nodesOfType(Interaction.class).isEmpty());
    }

    @Test
    void cycleAt_setsCycleDetected() {
        ChainNode node = ChainNode.of(null, "a", 3, "ROOT");

        ChainWalk walk = ChainWalk.builder("id").cycleAt(node).build();

        assertTrue(walk.cycleDetected());
        assertEquals(node, walk.cycleAt());
    }

    @Test
    void cycleAt_null_clearsCycleDetected() {
        ChainWalk walk = ChainWalk.builder("id").cycleAt(null).build();

        assertFalse(walk.cycleDetected());
        assertNull(walk.cycleAt());
    }

    @Test
    void aborted_null_clearsFlag() {
        ChainWalk walk = ChainWalk.builder("id").aborted("boom").aborted(null).build();

        assertFalse(walk.aborted());
        assertNull(walk.abortReason());
    }

    @Test
    void aborted_withReason_setsFlagAndReason() {
        ChainWalk walk = ChainWalk.builder("id").aborted("Failed to find interaction: X").build();

        assertTrue(walk.aborted());
        assertEquals("Failed to find interaction: X", walk.abortReason());
    }

    @Test
    void node_tracksMaxDepthReached() {
        ChainWalk walk = ChainWalk.builder("id")
                .node(ChainNode.of(null, "a", 0, "ROOT"))
                .node(ChainNode.of(null, "b", 3, "ROOT"))
                .node(ChainNode.of(null, "c", 1, "ROOT"))
                .build();

        assertEquals(3, walk.maxDepthReached());
    }

    @Test
    void node_null_isIgnored() {
        ChainWalk walk = ChainWalk.builder("id").node(null).build();

        assertTrue(walk.nodes().isEmpty());
    }
}
