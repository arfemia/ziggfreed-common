package com.ziggfreed.common.objectives.marker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * The hub's listener table: one listener per id, a disconnect reaches every listener whatever another
 * does, and the characters a scope hands a listener are read-only.
 */
class QuestMarkersTest {

    @BeforeEach
    @AfterEach
    void reset() {
        QuestMarkers.resetForTests();
    }

    @Test
    void aListenerIsAddedOncePerIdWithoutRegardToCase() {
        QuestMarkerListener first = scope -> { };
        QuestMarkerListener second = scope -> { };
        QuestMarkers.addListener("Waypoints", first);
        QuestMarkers.addListener("waypoints", second);
        assertEquals(1, QuestMarkers.listenerCount(), "a second listener under the same id is ignored");
    }

    @Test
    void aListenerReadsTheIndexedCharactersButCannotChangeThem() {
        Set<Ref<EntityStore>> indexed = ConcurrentHashMap.newKeySet();
        QuestMarkerScope scope = new QuestMarkerScope(null, null, null, new Ref<>((Store<EntityStore>) null),
                UUID.randomUUID(), Subject.of(UUID.randomUUID(), "tester"), indexed);
        Ref<EntityStore> host = new Ref<>((Store<EntityStore>) null);

        assertThrows(UnsupportedOperationException.class, () -> scope.hosts().add(host));
        indexed.add(host);
        assertTrue(scope.hosts().contains(host), "a view of the hub's index, not a copy");
    }

    @Test
    void aDisconnectReachesEveryListenerEvenWhenOneThrows() {
        List<UUID> forgotten = new ArrayList<>();
        QuestMarkers.addListener("throws", new QuestMarkerListener() {
            @Override
            public void evaluate(QuestMarkerScope scope) {
            }

            @Override
            public void forget(UUID viewerId) {
                throw new IllegalStateException("boom");
            }
        });
        QuestMarkers.addListener("records", new QuestMarkerListener() {
            @Override
            public void evaluate(QuestMarkerScope scope) {
            }

            @Override
            public void forget(UUID viewerId) {
                forgotten.add(viewerId);
            }
        });
        UUID viewer = UUID.randomUUID();

        QuestMarkers.forgetEverywhere(viewer);

        assertEquals(List.of(viewer), forgotten);
    }
}
