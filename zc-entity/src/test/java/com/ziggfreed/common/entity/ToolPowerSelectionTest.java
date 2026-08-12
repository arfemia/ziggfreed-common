package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The pick a caller makes out of a tool's SPREAD of gather powers.
 *
 * <p>The fixture matters: a real gathering tool is not authored with one power, it is authored with
 * a dozen - a hatchet's high {@code Woods} number sitting beside token powers for soils, rocks and
 * every ore tier - so every assertion here is about a shape production content actually has. The
 * numbers themselves are invented for this file and are deliberately NOT any shipped tool's, so a
 * balance pass on a real hatchet never drags a test with it.
 *
 * <p><b>Why the fixture is a MAP and not an {@code ItemToolSpec[]}</b>: constructing one touches
 * {@code ItemToolSpec}'s static {@code CODEC}, whose initializer reaches {@code RangeValidator} ->
 * {@code HytaleLogger} and throws in a unit JVM with no log manager - the same class-init trap
 * {@code interaction.type.InteractionTypeSpec} exists to route around. A map is exactly what
 * {@link HeldItemUtil#toolPowersOf} produces anyway, so the selection contract is pinned in full;
 * the spec-array fold above it is unchanged engine-facing code covered by in-game smoke.
 */
class ToolPowerSelectionTest {

    /**
     * A multi-gather-type tool as the fold hands it over: several types at once, keys lowercased,
     * one clearly dominant type that is NOT the one a wood station would ask about (so a test that
     * confuses the aggregate with the addressed read cannot accidentally pass).
     */
    private static Map<String, Double> hatchetLike() {
        Map<String, Double> powers = new LinkedHashMap<>();
        powers.put("softblocks", 1.0);
        powers.put("woods", 0.6);
        powers.put("rocks", 0.04);
        powers.put("oremithril", 0.02);
        return powers;
    }

    // ==================== the addressed pick ====================

    @Test
    void aNamedGatherTypeSelectsThatSpecAlone() {
        Map<String, Double> powers = hatchetLike();

        assertEquals(0.6, HeldItemUtil.toolPowerFor(powers, "Woods"), 1e-9);
        assertEquals(0.04, HeldItemUtil.toolPowerFor(powers, "Rocks"), 1e-9);
        assertEquals(0.02, HeldItemUtil.toolPowerFor(powers, "OreMithril"), 1e-9);
    }

    @Test
    void theNameIsMatchedCaseInsensitivelyBecauseAnAuthorTypesItByHand() {
        Map<String, Double> powers = hatchetLike();

        assertEquals(0.6, HeldItemUtil.toolPowerFor(powers, "woods"), 1e-9);
        assertEquals(0.6, HeldItemUtil.toolPowerFor(powers, "WOODS"), 1e-9);
        assertEquals(0.6, HeldItemUtil.toolPowerFor(powers, "  WoOdS  "), 1e-9);
    }

    @Test
    void aGatherTypeTheToolCannotDoAtAllReadsAsUnanswerableNotAsZero() {
        Map<String, Double> powers = hatchetLike();

        assertNull(HeldItemUtil.toolPowerFor(powers, "Benches"),
                "cannot do this job is a different answer from does it badly - a fabricated 0 would"
                        + " let a bounds-less gate pass and would look like a real reading in a"
                        + " weighted term");
        assertNull(HeldItemUtil.toolPowerFor(powers, "VolcanicRocks"));
    }

    @Test
    void anAddressedReadIsNotTheAggregateInDisguise() {
        Map<String, Double> powers = hatchetLike();

        assertEquals(0.6, HeldItemUtil.toolPowerFor(powers, "Woods"), 1e-9,
                "the dominant SoftBlocks power must not leak into a Woods question");
    }

    // ==================== the aggregate form (today's no-Param behaviour, pinned) ====================

    @Test
    void noNameAnswersTheBestPowerOfAnyType() {
        assertEquals(1.0, HeldItemUtil.toolPowerFor(hatchetLike(), null), 1e-9,
                "the aggregate form is the MAX across every gather type, unchanged");
    }

    @Test
    void aBlankNameIsTheAggregateFormNotAMissingGatherType() {
        assertEquals(1.0, HeldItemUtil.toolPowerFor(hatchetLike(), ""), 1e-9);
        assertEquals(1.0, HeldItemUtil.toolPowerFor(hatchetLike(), "   "), 1e-9);
    }

    @Test
    void theAggregateIsIndependentOfTheOrderTheSpecsWereAuthoredIn() {
        Map<String, Double> ascending = new LinkedHashMap<>();
        ascending.put("rocks", 0.04);
        ascending.put("woods", 0.6);
        Map<String, Double> descending = new LinkedHashMap<>();
        descending.put("woods", 0.6);
        descending.put("rocks", 0.04);

        assertEquals(HeldItemUtil.toolPowerFor(ascending, null),
                HeldItemUtil.toolPowerFor(descending, null));
    }

    @Test
    void aNullEntryIsSkippedRatherThanWinningTheAggregate() {
        Map<String, Double> powers = new LinkedHashMap<>();
        powers.put("woods", null);
        powers.put("rocks", 0.04);

        assertEquals(0.04, HeldItemUtil.toolPowerFor(powers, null), 1e-9);
        assertNull(HeldItemUtil.toolPowerFor(powers, "Woods"));
    }

    // ==================== nothing to read ====================

    @Test
    void nothingHeldIsUnanswerableInBothForms() {
        assertNull(HeldItemUtil.toolPowerFor(Map.of(), "Woods"));
        assertNull(HeldItemUtil.toolPowerFor(Map.of(), null));
        assertNull(HeldItemUtil.toolPowerFor(null, "Woods"));
        assertNull(HeldItemUtil.toolPowerFor(null, null));
    }

    @Test
    void aToolWithOnlyUnreadablePowersIsUnanswerableRatherThanZero() {
        Map<String, Double> powers = new LinkedHashMap<>();
        powers.put("woods", null);

        assertNull(HeldItemUtil.toolPowerFor(powers, null),
                "no readable power anywhere must not collapse to a 0 that reads as a real answer");
    }
}
