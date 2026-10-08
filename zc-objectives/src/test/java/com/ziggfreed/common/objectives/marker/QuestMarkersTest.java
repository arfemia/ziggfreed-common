package com.ziggfreed.common.objectives.marker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The hub's listener table: one listener per id, and a disconnect reaches every listener whatever another does. */
class QuestMarkersTest {

    @BeforeEach
    @AfterEach
    void reset() {
        QuestMarkers.resetForTests();
    }

    @Test
    void aListenerIsAddedOncePerIdWithoutRegardToCase() {
        QuestMarkerListener listener = scope -> { };
        QuestMarkers.addListener("Waypoints", listener);
        QuestMarkers.addListener("waypoints", listener);
        assertEquals(1, QuestMarkers.listenerCount());
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
