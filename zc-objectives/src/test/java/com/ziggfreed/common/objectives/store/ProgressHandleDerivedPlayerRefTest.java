package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The handle DERIVES the player's reference from the store it already holds rather than carrying
 * one alongside it. Two things follow, and both are what this pins.
 *
 * <p>First, nothing can hand the handle a reference that disagrees with the ref beside it, because
 * there is no component to hand: the record is {@code (store, ref)} and the reference is a method.
 * A carried one could be built from a different entity, or simply go stale while the ref did not,
 * and nothing anywhere would say so.
 *
 * <p>Second, a ref that no longer resolves anything answers {@code null} rather than throwing,
 * which is what every reader of this already branches on. That half is provable with no world at
 * all: a {@link Ref} constructed with no index is exactly that state, and the accessor has to
 * answer for it without reaching the store.
 *
 * <p><b>What this cannot reach, and why.</b> The other two branches both need a LIVE store: a valid
 * ref whose entity carries no {@code PlayerRef} component (a mob, a prop), and the positive case of
 * a player answering with their own reference. Neither is constructible here - the engine's
 * {@code Store} needs a component registry, external world data and a resource storage, and a
 * {@link Ref} needs a store in turn, which is why the two handles below are passed as {@code null}
 * despite being declared non-null. Both branches are in-game smoke, like the rest of this module's
 * engine-touching half. What stands in for them is the DECLARATION: the handle still answers for
 * {@link PlayerRef} through {@link ProgressHandle#answersFor}, so a reward that runs a command with
 * the player's own authority still finds somebody to run as, and {@code facet} reads the same
 * derived accessor rather than a second source that could answer differently.
 */
class ProgressHandleDerivedPlayerRefTest {

    /**
     * A ref in the state the engine leaves one in when it resolves nothing: no index assigned.
     *
     * <p>Its store argument is null because a real one cannot be built in a unit JVM (see the class
     * javadoc). Nothing here reads through it: the accessor under test refuses on
     * {@code ref.isValid()} before it touches the store, which is exactly the behaviour being
     * pinned.
     */
    private static Ref<EntityStore> unresolvedRef() {
        return new Ref<>(null);
    }

    @Test
    void theReferenceIsNotACarriedComponent() {
        RecordComponent[] components = ProgressHandle.class.getRecordComponents();
        assertEquals(2, components.length,
                "the handle is (store, ref) and nothing else - a third component would be a second"
                        + " input a caller could pass out of step with the ref beside it");
        assertTrue(Arrays.stream(components).noneMatch(c -> c.getType() == PlayerRef.class),
                "a carried PlayerRef can disagree with the entity the ref names; a derived one"
                        + " cannot, which is the whole point of reading it off the store");
    }

    @Test
    void theReferenceIsADerivedAccessor() {
        assertDoesNotThrow(() -> ProgressHandle.class.getDeclaredMethod("playerRef"),
                "the handle still answers the question, it just answers it by reading rather than"
                        + " by remembering");
    }

    @Test
    void aRefThatResolvesNothingAnswersNullRatherThanThrowing() {
        ProgressHandle handle = new ProgressHandle(null, unresolvedRef());

        assertNull(handle.playerRef(),
                "no longer a live entity: the honest answer is nobody, and every caller already"
                        + " branches on it");
        assertNull(handle.facet(PlayerRef.class),
                "the facet reads the same derived value, so it degrades the same way instead of"
                        + " throwing into whichever reward asked");
    }

    @Test
    void itStillDeclaresThatItCanAnswerForAPlayerRef() {
        assertTrue(ProgressHandle.answersFor(PlayerRef.class),
                "deriving the reference must not quietly withdraw the handle from the readers that"
                        + " ask a subject for one - a command reward run with the player's own"
                        + " authority would then have nobody to run as");
    }
}
