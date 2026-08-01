package com.ziggfreed.common.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link ChainNode} value-type tests. Uses {@code ChainNode.of(null, ...)} throughout - a real
 * {@code Interaction} cannot be instantiated in a unit JVM (its class-init chains into the
 * RangeValidator/HytaleLogger trap), so every test here exercises the synthetic-entry path.
 */
class ChainNodeTest {

    @Test
    void of_nullInteractionWithId_usesCallerSuppliedId() {
        ChainNode node = ChainNode.of(null, "Some_Interaction", 2, "TAG");

        assertNull(node.interaction());
        assertEquals("Some_Interaction", node.id());
        assertEquals(2, node.depth());
        assertEquals("TAG", node.tag());
    }

    @Test
    void of_nullTag_rendersRoot() {
        ChainNode node = ChainNode.of(null, "x", 0, null);

        assertEquals("ROOT", node.tag());
    }

    @Test
    void of_nullIdAndNullInteraction_rendersEmptyId() {
        ChainNode node = ChainNode.of(null, null, 0, "TAG");

        assertEquals("", node.id());
    }

    @Test
    void of_depthZero_isADirectChildOfRoot() {
        ChainNode node = ChainNode.of(null, "x", 0, "ROOT");

        assertEquals(0, node.depth());
    }

    @Test
    void toString_isNonNull() {
        ChainNode node = ChainNode.of(null, "x", 0, "ROOT");

        assertNotNull(node.toString());
    }
}
