package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the generic guarded {@link ObserverRegistry}: fire-in-order, and the
 * key contract - a listener that throws is isolated (it neither throws out of {@code fire} nor
 * blocks the listeners after it). Pure Java, no engine.
 */
class ObserverRegistryTest {

    @Test
    void firesEveryListenerInRegistrationOrder() {
        ObserverRegistry<String> reg = new ObserverRegistry<>();
        List<String> seen = new ArrayList<>();
        reg.register(e -> seen.add("A:" + e));
        reg.register(e -> seen.add("B:" + e));
        reg.register(e -> seen.add("C:" + e));

        reg.fire("evt");
        assertEquals(List.of("A:evt", "B:evt", "C:evt"), seen, "all listeners fired in order");
    }

    @Test
    void aThrowingListenerIsIsolatedAndTheRestStillRun() {
        ObserverRegistry<String> reg = new ObserverRegistry<>("test");
        List<String> seen = new ArrayList<>();
        reg.register(e -> seen.add("before"));
        reg.register(e -> { throw new IllegalStateException("bad listener"); });
        reg.register(e -> seen.add("after"));

        assertDoesNotThrow(() -> reg.fire("evt"), "one throwing listener never throws out of fire");
        assertTrue(seen.contains("before") && seen.contains("after"),
                "listeners on both sides of the throwing one still ran");
        assertEquals(2, seen.size());
    }

    @Test
    void fireWithNoListenersIsANoOp() {
        ObserverRegistry<Integer> reg = new ObserverRegistry<>();
        assertDoesNotThrow(() -> reg.fire(1), "firing an empty registry does nothing");
    }
}
