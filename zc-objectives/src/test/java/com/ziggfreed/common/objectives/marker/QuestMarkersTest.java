package com.ziggfreed.common.objectives.marker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The hub's scope: the characters a scope hands a surface are read-only.
 */
class QuestMarkersTest {

    @BeforeEach
    @AfterEach
    void reset() {
        QuestMarkers.resetForTests();
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
}
