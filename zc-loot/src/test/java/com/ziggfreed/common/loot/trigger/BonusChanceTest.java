package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;

/** A row's Chance read as a percent, and the one rule a draw follows. */
class BonusChanceTest {

    private final List<String> warnings = new ArrayList<>();

    private double fraction(FactorFormula chance, double unwritten) {
        return BonusChance.fraction(chance, unwritten, new FactorRegistry("bonus-chance-test"),
                FactorContext.builder().build(), "fixture_row", warnings::add);
    }

    private static FactorFormula percent(double base) {
        return FactorFormula.of(base, null, null);
    }

    /** A draw answering {@code value} that counts how often it was asked. */
    private static DoubleSupplier draw(double value, AtomicInteger asked) {
        return () -> {
            asked.incrementAndGet();
            return value;
        };
    }

    @Test
    void anAuthoredChanceIsAPercent() {
        assertEquals(0.05, fraction(percent(5.0), 1.0), 1e-9, "5 is five percent");
        assertEquals(0.005, fraction(percent(0.5), 1.0), 1e-9, "0.5 is half a percent, never fifty");
    }

    @Test
    void anUnwrittenChanceIsTheMomentsOwnDefault() {
        assertEquals(1.0, fraction(null, 1.0), 1e-9);
        assertEquals(0.42, fraction(null, 0.42), 1e-9);
        assertEquals(0.42, fraction(FactorFormula.of(null, null, null), 0.42), 1e-9,
                "an empty formula is no Chance at all");
    }

    @Test
    void theAnswerIsHeldInsideZeroToOne() {
        assertEquals(1.0, fraction(percent(500.0), 0.0), 1e-9);
        assertEquals(0.0, fraction(percent(-500.0), 0.0), 1e-9);
        assertEquals(0.2, fraction(FactorFormula.of(90.0, null, FactorFormula.Clamp.of(0.0, 20.0)), 0.0), 1e-9);
        assertEquals(1.0, fraction(null, 7.0), 1e-9, "an out-of-range default is held too");
    }

    @Test
    void notANumberIsNoChanceRatherThanACertainty() {
        assertEquals(0.0, fraction(null, Double.NaN), 1e-9);
    }

    @Test
    void aFactorNothingAnswersAddsNothing() {
        FactorFormula chance = FactorFormula.of(10.0,
                new FactorFormula.Term[] {FactorFormula.Term.of("nosuchmod:whatever", null, 1000.0)}, null);

        assertEquals(0.10, fraction(chance, 0.0), 1e-9);
        assertTrue(warnings.isEmpty(), "an unanswered term is not an unreadable formula");
    }

    @Test
    void atOrAboveCertaintyFiresWithoutADraw() {
        AtomicInteger asked = new AtomicInteger();
        assertTrue(BonusChance.fires(1.0, draw(0.99, asked)));
        assertTrue(BonusChance.fires(1.5, draw(0.99, asked)));
        assertEquals(0, asked.get());
    }

    @Test
    void atOrBelowZeroOrNotANumberNeverFiresAndNeverDraws() {
        AtomicInteger asked = new AtomicInteger();
        assertFalse(BonusChance.fires(0.0, draw(0.0, asked)));
        assertFalse(BonusChance.fires(-0.5, draw(0.0, asked)));
        assertFalse(BonusChance.fires(Double.NaN, draw(0.0, asked)));
        assertEquals(0, asked.get());
    }

    @Test
    void inBetweenOneDrawMustLandStrictlyBelow() {
        AtomicInteger below = new AtomicInteger();
        assertTrue(BonusChance.fires(0.3, draw(0.1, below)));
        assertEquals(1, below.get());

        AtomicInteger equal = new AtomicInteger();
        assertFalse(BonusChance.fires(0.3, draw(0.3, equal)), "a draw equal to the chance misses");
        assertEquals(1, equal.get());
    }
}
