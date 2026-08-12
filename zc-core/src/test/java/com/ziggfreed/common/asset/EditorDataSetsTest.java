package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.event.EventRegistry;

/**
 * The one thing this class must never do: take a server down because the Asset Editor is not on it.
 *
 * <p>An editor dataset is pure authoring convenience - the pick list a field offers - and the
 * modules that serve it are optional. A registration that throws (no editor module, a registry that
 * cannot accept the listener) has to degrade to a plain free-text field, so the guard is the whole
 * contract and it is pinned here.
 */
class EditorDataSetsTest {

    /**
     * A registry that cannot actually accept a registration, standing in for a server whose Asset
     * Editor module is absent: the attempt fails inside the helper exactly as a missing event class
     * would.
     */
    private static EventRegistry unusableRegistry() {
        return new EventRegistry(new ArrayList<>(), () -> true, null, null);
    }

    @Test
    void registeringADataSetOnAServerThatCannotServeItIsSilent() {
        EventRegistry registry = unusableRegistry();

        assertDoesNotThrow(() -> EditorDataSets.live(registry, EditorDataSets.FACTORS, List::of));
        assertDoesNotThrow(() -> EditorDataSets.fixed(registry, EditorDataSets.PLACEMENT_FACTORS, "a", "b"));
    }

    @Test
    void aThrowingSourceIsSurvivedToo() {
        EventRegistry registry = unusableRegistry();

        assertDoesNotThrow(() -> EditorDataSets.live(registry, EditorDataSets.FACTORS, () -> {
            throw new IllegalStateException("catalog blew up");
        }));
    }

    @Test
    void theDataSetIdsAreNamespacedAndDistinct() {
        assertTrue(EditorDataSets.FACTORS.startsWith("ziggfreedcommon:"),
                "an unnamespaced id could collide with a first-party or third-party set");
        assertTrue(EditorDataSets.PLACEMENT_FACTORS.startsWith("ziggfreedcommon:"));
        assertNotEquals(EditorDataSets.FACTORS, EditorDataSets.PLACEMENT_FACTORS,
                "two ids so the placement list can narrow later without moving every codec");
    }
}
