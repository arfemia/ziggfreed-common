package com.ziggfreed.common.subject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The reference-only payout identity: that a payout with nobody behind it stays a well-formed
 * subject (named, filed under the anonymous id) and honestly answers "no player" to every facet
 * read, rather than throwing or guessing.
 */
class PlayerRefSubjectHandleTest {

    @Test
    void anOfflinePayoutIsFiledUnderTheAnonymousId() {
        Subject subject = PlayerRefSubjectHandle.subjectFor(null, "alice");

        assertEquals(new UUID(0L, 0L), subject.id(),
                "nothing keys state by the anonymous id, so it is safe to file under");
        assertEquals("alice", subject.name(),
                "the username is passed in, never read back off a reference that may not be there");
    }

    @Test
    void aReferencelessHandleAnswersNoPlayerToEveryFacetRead() {
        Subject subject = PlayerRefSubjectHandle.subjectFor(null, "alice");

        assertNull(subject.handleAs(Player.class), "no reference, so no live entity to resolve");
        assertNull(subject.handleAs(PlayerRef.class));
        assertNull(subject.handleAs(String.class), "a type the handle has nothing to offer for");
    }
}
