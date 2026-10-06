package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.subject.Subject;

/**
 * When the Quests and Achievements tabs show: the catalogue has something in it, the player has a
 * subject, and the owner's system switch (the same read a consumer's per-player toggle feeds) is on.
 */
class ObjectiveBookMenuTest {

    private Subject player;

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Test
    void aTabShowsOnlyWithACatalogueASubjectAndTheSystemOn() {
        assertTrue(ObjectiveBookMenu.shows(3, player, subject -> true));
        assertFalse(ObjectiveBookMenu.shows(0, player, subject -> true), "an empty catalogue has nothing to list");
        assertFalse(ObjectiveBookMenu.shows(3, null, subject -> true), "no subject, nobody to show it to");
        assertFalse(ObjectiveBookMenu.shows(3, player, subject -> false), "the system is off for this player");
    }

    @Test
    void theOwnersSystemSwitchReachesEachTabAlone() {
        ProgressionRuntime.registrar("yourmod").systemGate((system, subject) -> system != ProgressionSystem.QUEST);

        assertFalse(ObjectiveBookMenu.shows(1, player, ObjectiveBookMenu.systemOn(ProgressionSystem.QUEST)));
        assertTrue(ObjectiveBookMenu.shows(1, player, ObjectiveBookMenu.systemOn(ProgressionSystem.ACHIEVEMENT)),
                "a refusal aimed at quests leaves achievements alone");
    }
}
