package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.subject.Subject;

/**
 * The three things the library's default handle has to do beyond being itself. It stands in for the
 * live {@link Player}, since {@code subject.handleAs(Player.class)} is how every ready-made reward
 * handler in this library finds one. It stands in for the {@link PlayerRef}, which is what a reward
 * running a command with the PLAYER's own authority asks a subject for. And it stands in for the
 * {@link ZigProgressComponent}, the shape the two default stores ask a subject for FIRST, so that a
 * consumer bringing its own handle needs only to answer for the component to keep those stores as
 * THE store. Without the first a collected quest pays out nothing at all and still reports success;
 * without the second such a reward refuses rather than running; without the third this handle would
 * be asking a courtesy of consumers it does not extend itself.
 *
 * <p>Building a real handle needs a world, so what is pinned here is the DECLARATION and the
 * DECISION; the component read behind them belongs to in-game smoke, like the rest of the
 * engine-touching half of this module.
 */
class ProgressHandleFacetTest {

    @Test
    void theHandleDeclaresItselfAnswerable() {
        assertTrue(Subject.HandleFacets.class.isAssignableFrom(ProgressHandle.class),
                "a handle that answers only for itself leaves every shared reward kind unable to"
                        + " find the player it is supposed to pay");
    }

    @Test
    void itStandsInForThePlayerAndThePlayerRef() {
        assertTrue(ProgressHandle.answersFor(Player.class));
        assertTrue(ProgressHandle.answersFor(PlayerRef.class),
                "a command reward asked to run with the player's own authority looks a subject up as"
                        + " a PlayerRef, so a handle that refuses one leaves it with nobody to run as");
        assertFalse(ProgressHandle.answersFor(String.class));
    }

    @Test
    void itStandsInForTheProgressComponent() {
        assertTrue(ProgressHandle.answersFor(ZigProgressComponent.class),
                "the two default stores ask a subject for the component FIRST, so a handle that"
                        + " refused one would leave them falling back to a lookup they only keep for"
                        + " the handles that came before this");
    }
}
