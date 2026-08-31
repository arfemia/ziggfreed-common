package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.util.PeriodMath;

/**
 * The two pure halves of how a commerce screen reads: what an authored argument turns into, and how
 * long is left on a clock.
 *
 * <p>The argument half is the one that matters. A family generated per skill writes the skill ID
 * into its own text arguments, so a page that passes them through renders {@code MINING experience}
 * in every language on earth - a shipped-content bug that no translation can fix. These assertions
 * pin that the resolver is asked about every argument, that an unanswered one survives verbatim, and
 * that a resolver blowing up costs its own word rather than the line.
 */
class CommerceTextTest {

    @Test
    @DisplayName("a generated id becomes whatever the consumer says it means")
    void aResolverAnswersEveryArgument() {
        Object[] args = CommerceText.args(List.of("MINING", "COMBAT"),
                authored -> "MINING".equals(authored) ? "Mining" : null);

        assertArrayEquals(new Object[] {"Mining", "COMBAT"}, args,
                "an answered argument is replaced and an unanswered one is left exactly as authored");
    }

    @Test
    @DisplayName("with nothing installed, an argument reads as the literal an author typed")
    void theDefaultIsRaw() {
        assertArrayEquals(new Object[] {"MINING"},
                CommerceText.args(List.of("MINING"), CommerceText.RAW_ARGS));
        assertArrayEquals(new Object[] {"MINING"}, CommerceText.args(List.of("MINING"), null));
    }

    @Test
    @DisplayName("a resolver that throws costs its own word, never the whole line")
    void aThrowingResolverIsGuarded() {
        Object[] args = CommerceText.args(List.of("BOOM", "KEPT"), authored -> {
            if ("BOOM".equals(authored)) {
                throw new IllegalStateException("a consumer's naming blew up");
            }
            return null;
        });

        assertArrayEquals(new Object[] {"BOOM", "KEPT"}, args);
    }

    @Test
    @DisplayName("@amount answers with a grouped number when nothing else claims it")
    void theAmountSentinelStillWorks() {
        Object[] args = CommerceText.args(List.of(ContentTextAsset.ARG_AMOUNT), null, 12500L);

        assertEquals(1, args.length);
        assertEquals(String.class, args[0].getClass(), "a digit needs no translating");
        assertFalse(args[0].toString().isBlank());
    }

    @Test
    @DisplayName("a consumer may answer @amount itself, and its answer wins")
    void aResolverOutranksTheSentinel() {
        assertArrayEquals(new Object[] {"lots"},
                CommerceText.args(List.of(ContentTextAsset.ARG_AMOUNT),
                        authored -> ContentTextAsset.ARG_AMOUNT.equals(authored) ? "lots" : null,
                        7L));
    }

    @Test
    @DisplayName("no arguments is not an error, it is the common case")
    void noArgumentsIsFine() {
        assertEquals(0, CommerceText.args(null, CommerceText.RAW_ARGS).length);
        assertEquals(0, CommerceText.args(List.of(), CommerceText.RAW_ARGS).length);
    }

    // ==================== the clock ====================

    @Test
    @DisplayName("a countdown reads in the two coarsest units that still say something")
    void theCountdownPicksItsUnits() {
        assertEquals("2d 6h", CommerceText.countdown(2 * PeriodMath.DAY_MS + 6 * PeriodMath.HOUR_MS));
        assertEquals("6h 14m",
                CommerceText.countdown(6 * PeriodMath.HOUR_MS + 14 * PeriodMath.MINUTE_MS));
        assertEquals("14m 03s",
                CommerceText.countdown(14 * PeriodMath.MINUTE_MS + 3 * PeriodMath.SECOND_MS));
        assertEquals("43s", CommerceText.countdown(43 * PeriodMath.SECOND_MS));
    }

    @Test
    @DisplayName("a period already over, or one that never turns over, reads as a dash")
    void nothingToCountReadsAsADash() {
        assertEquals("-", CommerceText.countdown(0L));
        assertEquals("-", CommerceText.countdown(-1L));
        assertEquals("-", CommerceText.countdown(Long.MAX_VALUE),
                "an unauthored rotation never turns over, so there is no countdown to show");
    }

    // ==================== ids ====================

    @Test
    @DisplayName("ids compare the way every other id in this library does")
    void idsAreCaseInsensitive() {
        assertTrue(CommerceText.sameId("Daily", " daily "));
        assertFalse(CommerceText.sameId("Daily", "Weekly"));
        assertFalse(CommerceText.sameId(null, null), "nothing is not the same thing as nothing");
        assertEquals("daily", CommerceText.normalize(" Daily "));
        assertEquals("", CommerceText.normalize(null));
    }
}
