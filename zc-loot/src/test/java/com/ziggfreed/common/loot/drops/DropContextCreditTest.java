package com.ziggfreed.common.loot.drops;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * Who a native roll is credited to, driven as the engine drives it: a break says its breaker, then the engine rolls
 * the block's list; the death-drop pass marks one entity, then rolls its list, then the next. Each roll answers the
 * moment that caused it, never an earlier one (a verifier found both a break's and a death's credit reaching a later
 * roll).
 */
class DropContextCreditTest {

    private static final String ORE = "drop_ore";
    private static final String TRORK = "drop_trork";
    private static final String WOLF = "drop_wolf";

    @BeforeEach
    void freshTick() {
        DropContext.markLive();
        DropContext.clear();
    }

    private static DropContext.Who who(String name) {
        return new DropContext.Who(Subject.of(UUID.randomUUID(), name),
                new Ref<EntityStore>((Store<EntityStore>) null), null, null);
    }

    /** One native roll of {@code list}, as the engine starts one: a fresh set naming it. */
    private static DropContext.Who roll(String list) {
        Set<String> rolled = new HashSet<>();
        rolled.add(list);
        return DropContext.whoFor(rolled);
    }

    @Test
    void aBreaksCreditAnswersItsOwnRollAndNoLaterOne() {
        DropContext.Who miner = who("miner");
        DropContext.breaking(miner, Set.of(ORE));
        assertSame(miner, roll("Drop_Ore"), "the roll straight after the break is the break's, in any case");
        assertNull(roll(ORE), "a later roll of the same list is nobody's");
    }

    @Test
    void aBreakCreditingNobodyForgetsTheLastBreaker() {
        DropContext.breaking(who("first"), Set.of(ORE));
        DropContext.breakingForNobody();
        assertNull(roll(ORE), "a placed block broken next never rolls on the earlier breaker's credit");
    }

    @Test
    void aRollOfAnotherListLeavesTheBreaksCredit() {
        DropContext.Who miner = who("miner");
        DropContext.breaking(miner, Set.of(ORE));
        assertNull(roll(WOLF), "a roll of a list the block cannot drop is not the break's");
        assertSame(miner, roll(ORE), "and the break's own roll still is");
    }

    @Test
    void eachDeathsRollAnswersItsOwnVictimWhateverOrderTheyAreMarkedIn() {
        AtomicBoolean a = new AtomicBoolean();
        AtomicBoolean b = new AtomicBoolean();
        AtomicBoolean c = new AtomicBoolean();
        DropContext.Who killedA = who("a");
        DropContext.Who killedB = who("b");
        DropContext.Who killedC = who("c");
        DropContext.dying(a::get, "Drop_Trork", killedA);
        DropContext.dying(b::get, "Drop_Trork", killedB);
        DropContext.dying(c::get, "Drop_Trork", killedC);

        c.set(true);
        assertSame(killedC, roll(TRORK));
        a.set(true);
        assertSame(killedA, roll(TRORK));
        b.set(true);
        assertSame(killedB, roll(TRORK));
        assertNull(roll(TRORK), "a roll of the list once every death has dropped is no kill's");
    }

    @Test
    void aDeathWhoseRollAskedNothingIsSpentByTheNextAsk() {
        AtomicBoolean z = new AtomicBoolean();
        AtomicBoolean y = new AtomicBoolean();
        AtomicBoolean x = new AtomicBoolean();
        DropContext.Who killedZ = who("z");
        DropContext.Who killedY = who("y");
        DropContext.Who killedX = who("x");
        DropContext.dying(z::get, TRORK, killedZ);
        DropContext.dying(y::get, WOLF, killedY);
        DropContext.dying(x::get, TRORK, killedX);

        x.set(true);
        y.set(true);
        assertSame(killedY, roll(WOLF), "the wolf's roll is the wolf's");
        z.set(true);
        assertSame(killedZ, roll(TRORK), "Z's roll is Z's, never X's, whose own roll reached no table");
    }
}
