package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;
import com.ziggfreed.common.track.TrackDef;

/**
 * A respec's price grows with the track's best level (R665): {@code Respec.Cost} paid {@code Respec.PerLevel}
 * times per level, rounded up and at least once, every wallet and item of it, its Combine kept; unwritten, once.
 */
class RespecPriceTest {

    @Test
    void perLevelPaysCostThatManyTimesPerLevelRoundedUp() {
        assertEquals(1, TrackDef.timesAt(null, 40), "unwritten: Cost once, whatever the level");
        assertEquals(1, TrackDef.timesAt(0.0, 40), "0 or less: Cost once");
        assertEquals(5, TrackDef.timesAt(0.5, 10));
        assertEquals(2, TrackDef.timesAt(0.5, 3), "rounded up");
        assertEquals(1, TrackDef.timesAt(0.1, 1), "at least once");
        assertEquals(100, TrackDef.timesAt(1.0, 100));
    }

    @Test
    void timesMultipliesEveryComponentAndKeepsTheCombine() {
        Cost once = Cost.of(Cost.Combine.ANY, Map.of("Life_Essence", 3L),
                List.of(ItemCost.of("Ingredient_Bar_Copper", 2)), null);
        Cost grown = PickPayments.times(once, 4);
        assertEquals(12L, grown.amountOf("Life_Essence"));
        assertEquals(8, grown.items().get(0).count());
        assertEquals(Cost.Combine.ANY, grown.combine());
        assertSame(once, PickPayments.times(once, 1), "once is the price as written");
        assertSame(Cost.FREE, PickPayments.times(Cost.FREE, 9), "free stays free");
    }
}
