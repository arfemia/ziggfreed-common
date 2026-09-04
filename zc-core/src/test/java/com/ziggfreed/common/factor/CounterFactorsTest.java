package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lifetime-tally reading: the consumer's record answers it, a missing key reads as never done,
 * and everything that cannot be answered stays unanswerable rather than opening a gate.
 */
class CounterFactorsTest {

    private static final Map<String, Long> ALICE = Map.of("mob_kills", 40L, "mob_kills/Warden", 3L);

    private static final CounterSource RECORD = (ctx, key) -> {
        Long value = ALICE.get(key);
        return value == null ? 0L : value;
    };

    @BeforeEach
    @AfterEach
    void reset() {
        CounterFactors.resetForTests();
    }

    @Test
    void theSourceAnswersATotalAndABreakdownLineThroughOneGrammar() {
        FactorContext ctx = FactorContext.builder().param("mob_kills").build();

        assertEquals(40.0, CounterFactors.answer(RECORD, ctx, "mob_kills"));
        assertEquals(3.0, CounterFactors.answer(RECORD, ctx, "mob_kills/Warden"),
                "a category and a name joined by / is one line of the breakdown");
        assertEquals(0.0, CounterFactors.answer(RECORD, ctx, "mob_kills/Nothing"),
                "a thing never done reads as zero, which is why a condition needs a Min");
    }

    @Test
    void withNoSourceInstalledTheReadingIsUnanswerable() {
        assertNull(CounterFactors.answer(null, FactorContext.builder().build(), "mob_kills"),
                "a gate must not open because the mod keeping the tallies is absent");
        assertFalse(CounterFactors.isSourceFilled());
    }

    @Test
    void aSourceThatThrowsCostsTheReadingAndNothingElse() {
        CounterSource broken = (ctx, key) -> {
            throw new IllegalStateException("record unavailable");
        };
        assertNull(CounterFactors.answer(broken, FactorContext.builder().build(), "mob_kills"));
    }

    @Test
    void aSourceThatDeclinesReadsAsUnanswerable() {
        CounterSource noRecord = (ctx, key) -> null;
        assertNull(CounterFactors.answer(noRecord, FactorContext.builder().build(), "mob_kills"));
    }

    @Test
    void noKeyOrNoLivePlayerIsUnanswerableBeforeTheSourceIsEvenAsked() {
        CounterFactors.source((ctx, key) -> 99L);
        assertTrue(CounterFactors.isSourceFilled());

        assertNull(CounterFactors.resolve(FactorContext.builder().build()), "no Param, nothing to read");
        assertNull(CounterFactors.resolve(FactorContext.builder().param("mob_kills").build()),
                "no live player in the question, so nothing to read it about");
    }
}
