package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The pick a caller makes out of a tool's SPREAD of per-gather-type harvest-TIER GATES
 * ({@code ItemToolSpec.Quality}) - the mirror-image of {@link ToolPowerSelectionTest}, over a
 * DIFFERENT native field with the exact same selection contract.
 *
 * <p>The fixture matters the same way it does there: a real gathering tool is authored with a
 * tier per gather type (a pickaxe reaching a high tier on Rocks/Ore families while gating out
 * entirely on others it has no spec for at all), so every assertion is about a shape production
 * content actually has. The numbers are invented for this file, deliberately not any shipped
 * tool's, so a balance pass on a real pickaxe never drags this test with it.
 *
 * <p><b>Why the fixture is a MAP and not an {@code ItemToolSpec[]}</b>: constructing one touches
 * {@code ItemToolSpec}'s static {@code CODEC}, whose initializer reaches {@code RangeValidator} ->
 * {@code HytaleLogger} and throws in a unit JVM with no log manager - the same trap
 * {@link ToolPowerSelectionTest} documents. A map is exactly what
 * {@link HeldItemUtil#toolTiersOf} produces anyway, so the selection contract is pinned in full;
 * the spec-array fold above it is unchanged engine-facing code covered by in-game smoke.
 */
class ToolTierSelectionTest {

    /**
     * A multi-gather-type tool as the fold hands it over: several types at once, keys lowercased,
     * one clearly dominant type that is NOT the one a mining station would ask about first (so a
     * test that confuses the aggregate with the addressed read cannot accidentally pass).
     */
    private static Map<String, Double> pickaxeLike() {
        Map<String, Double> tiers = new LinkedHashMap<>();
        tiers.put("softblocks", 3.0);
        tiers.put("rocks", 1.0);
        tiers.put("oreiron", 2.0);
        tiers.put("oremithril", 0.0);
        return tiers;
    }

    // ==================== the addressed pick ====================

    @Test
    void aNamedGatherTypeSelectsThatSpecAlone() {
        Map<String, Double> tiers = pickaxeLike();

        assertEquals(1.0, HeldItemUtil.toolTierFor(tiers, "Rocks"), 1e-9);
        assertEquals(2.0, HeldItemUtil.toolTierFor(tiers, "OreIron"), 1e-9);
        assertEquals(0.0, HeldItemUtil.toolTierFor(tiers, "OreMithril"), 1e-9);
    }

    @Test
    void theNameIsMatchedCaseInsensitivelyBecauseAnAuthorTypesItByHand() {
        Map<String, Double> tiers = pickaxeLike();

        assertEquals(1.0, HeldItemUtil.toolTierFor(tiers, "rocks"), 1e-9);
        assertEquals(1.0, HeldItemUtil.toolTierFor(tiers, "ROCKS"), 1e-9);
        assertEquals(1.0, HeldItemUtil.toolTierFor(tiers, "  RoCkS  "), 1e-9);
    }

    @Test
    void aGatherTypeTheToolCannotDoAtAllReadsAsUnanswerableNotAsZero() {
        Map<String, Double> tiers = pickaxeLike();

        assertNull(HeldItemUtil.toolTierFor(tiers, "Woods"),
                "cannot do this job is a different answer from gated at the lowest tier - a"
                        + " fabricated 0 would let a bounds-less gate pass and would look like a"
                        + " real, if unqualified, reading");
        assertNull(HeldItemUtil.toolTierFor(tiers, "VolcanicRocks"));
    }

    @Test
    void anAddressedReadIsNotTheAggregateInDisguise() {
        Map<String, Double> tiers = pickaxeLike();

        assertEquals(1.0, HeldItemUtil.toolTierFor(tiers, "Rocks"), 1e-9,
                "the dominant SoftBlocks tier must not leak into a Rocks question");
    }

    // ==================== the aggregate form (mirrors tool_power's no-Param behaviour) ====================

    @Test
    void noNameAnswersTheBestTierOfAnyType() {
        assertEquals(3.0, HeldItemUtil.toolTierFor(pickaxeLike(), null), 1e-9,
                "the aggregate form is the MAX across every gather type, same as tool_power");
    }

    @Test
    void aBlankNameIsTheAggregateFormNotAMissingGatherType() {
        assertEquals(3.0, HeldItemUtil.toolTierFor(pickaxeLike(), ""), 1e-9);
        assertEquals(3.0, HeldItemUtil.toolTierFor(pickaxeLike(), "   "), 1e-9);
    }

    @Test
    void theAggregateIsIndependentOfTheOrderTheSpecsWereAuthoredIn() {
        Map<String, Double> ascending = new LinkedHashMap<>();
        ascending.put("rocks", 1.0);
        ascending.put("softblocks", 3.0);
        Map<String, Double> descending = new LinkedHashMap<>();
        descending.put("softblocks", 3.0);
        descending.put("rocks", 1.0);

        assertEquals(HeldItemUtil.toolTierFor(ascending, null),
                HeldItemUtil.toolTierFor(descending, null));
    }

    @Test
    void aNullEntryIsSkippedRatherThanWinningTheAggregate() {
        Map<String, Double> tiers = new LinkedHashMap<>();
        tiers.put("softblocks", null);
        tiers.put("rocks", 1.0);

        assertEquals(1.0, HeldItemUtil.toolTierFor(tiers, null), 1e-9);
        assertNull(HeldItemUtil.toolTierFor(tiers, "SoftBlocks"));
    }

    // ==================== nothing to read ====================

    @Test
    void nothingHeldIsUnanswerableInBothForms() {
        assertNull(HeldItemUtil.toolTierFor(Map.of(), "Rocks"));
        assertNull(HeldItemUtil.toolTierFor(Map.of(), null));
        assertNull(HeldItemUtil.toolTierFor(null, "Rocks"));
        assertNull(HeldItemUtil.toolTierFor(null, null));
    }

    @Test
    void aToolWithOnlyUnreadableTiersIsUnanswerableRatherThanZero() {
        Map<String, Double> tiers = new LinkedHashMap<>();
        tiers.put("rocks", null);

        assertNull(HeldItemUtil.toolTierFor(tiers, null),
                "no readable tier anywhere must not collapse to a 0 that reads as a real gate");
    }
}
