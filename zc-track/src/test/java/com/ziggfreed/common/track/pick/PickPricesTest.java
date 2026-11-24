package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;

/**
 * A pick's price that grows with each take ({@code Cost.Scaling}, the library's one growth rule) and the refund a kind
 * respec works out from it: a pick writing no {@code Scaling} costs its {@code Price} for every take, exactly as before;
 * one that writes it costs the take after n owned {@code base x 1.1^n} on the Exponential curve, freezes at its
 * {@code SoftCap}, and a run of takes costs the sum of each take's own price, a share of which comes back rounded down.
 */
class PickPricesTest {

    @Test
    void aPickWithNoScalingCostsItsPriceForEveryTake() throws IOException {
        TrackPickAsset plain = pick("{ \"Track\": \"Your_Track\", \"Repeat\": { \"Max\": 0 },"
                + " \"Cost\": { \"Price\": { \"Currencies\": { \"Your_Coin\": 250 },"
                + " \"Items\": [ { \"Item\": \"Your_Ore\", \"Count\": 2 } ] } } }");
        assertNull(plain.priceScaling(), "no Scaling group written");
        Cost base = PickPrices.base(plain);
        assertNull(base.scaling(), "a price with no growth carries none");
        for (int taken = 0; taken < 40; taken++) {
            Cost price = PickPrices.nth(base, taken);
            assertSame(base, price, "the same price at take " + (taken + 1));
        }
        assertEquals(250L * 3, PickPrices.paidFor(base, 3).amountOf("your_coin"), "three takes paid three prices");
    }

    @Test
    void anExponentialPriceGrowsWithEachTakeOwnedAndFreezesAtItsSoftCap() throws IOException {
        TrackPickAsset eternal = pick("{ \"Track\": \"Your_Track\", \"Repeat\": { \"Max\": 0 },"
                + " \"Cost\": { \"Price\": { \"Currencies\": { \"Your_Coin\": 500 } },"
                + " \"Scaling\": { \"Multiplier\": 1.1, \"SoftCap\": 3 } } }");
        Cost base = PickPrices.base(eternal);
        assertEquals(500L, PickPrices.nth(base, 0).amountOf("your_coin"), "the first take is the base");
        assertEquals(550L, PickPrices.nth(base, 1).amountOf("your_coin"));
        assertEquals(605L, PickPrices.nth(base, 2).amountOf("your_coin"));
        assertEquals(666L, PickPrices.nth(base, 3).amountOf("your_coin"), "500 x 1.1^3, rounded");
        assertEquals(666L, PickPrices.nth(base, 9).amountOf("your_coin"), "frozen past the soft cap");
        assertEquals(500L + 550L + 605L, PickPrices.paidFor(base, 3).amountOf("your_coin"),
                "a run of takes costs each take's own price");
        assertEquals(0L, PickPrices.paidFor(base, 0).amountOf("your_coin"), "no take, nothing paid");
    }

    @Test
    void aPolynomialPriceIsTheBaseTimesTheTakesNumberRaisedToItsMultiplier() throws IOException {
        TrackPickAsset ranks = pick("{ \"Track\": \"Your_Track\", \"Repeat\": { \"Max\": 0 },"
                + " \"Cost\": { \"Price\": { \"Items\": [ { \"Item\": \"Your_Ore\", \"Count\": 3 } ] },"
                + " \"Scaling\": { \"Curve\": \"polynomial\" } } }");
        Cost base = PickPrices.base(ranks);
        assertEquals(3, PickPrices.nth(base, 0).items().get(0).count(), "unwritten Multiplier is 1: a straight line");
        assertEquals(6, PickPrices.nth(base, 1).items().get(0).count());
        assertEquals(9, PickPrices.nth(base, 2).items().get(0).count());
    }

    @Test
    void aShareComesBackRoundedDownPerCurrencyAndForItems() {
        Cost paid = Cost.of(Cost.Combine.ALL, Map.of("life_essence", 1655L, "mastery_point", 13L),
                List.of(ItemCost.of("Your_Essence", 5)), null);
        Cost back = PickPrices.shared(paid, id -> "life_essence".equals(id) ? 0.8 : 1.0, 0.5);
        assertEquals(1324L, back.amountOf("life_essence"), "80 percent, rounded down");
        assertEquals(13L, back.amountOf("mastery_point"), "a currency at 1 comes back whole");
        assertEquals(2, back.items().get(0).count(), "half of five items, rounded down");

        Cost nothing = PickPrices.shared(paid, id -> 0.0, 0.0);
        assertTrue(nothing.isFree(), "a share of 0 keeps everything");
        Cost held = PickPrices.shared(paid, id -> 7.0, -3.0);
        assertEquals(1655L, held.amountOf("life_essence"), "a share past 1 gives back what was paid, never more");
        assertTrue(held.items().isEmpty(), "a share under 0 gives back nothing");
    }

    @Test
    void aPriceOfOnePartCountsItsFirstPartForEachTake() {
        Cost either = Cost.of(Cost.Combine.ANY, Map.of("your_coin", 100L), List.of(ItemCost.of("Your_Ore", 4)), null);
        Cost paid = PickPrices.paidFor(either, 2);
        assertEquals(200L, paid.amountOf("your_coin"), "the first part, twice");
        assertTrue(paid.items().isEmpty(), "never both parts of an either-or price");
    }

    @Test
    void anUnknownCurveReadsAsExponential() throws IOException {
        TrackPickAsset odd = pick("{ \"Track\": \"Your_Track\", \"Cost\": { \"Price\": { \"Currencies\":"
                + " { \"Your_Coin\": 100 } }, \"Scaling\": { \"Curve\": \"Steep\", \"Multiplier\": 2 } } }");
        assertTrue(odd.priceScaling().hasUnknownCurve());
        assertEquals(400L, PickPrices.nth(PickPrices.base(odd), 2).amountOf("your_coin"), "100 x 2^2");
    }

    private static TrackPickAsset pick(String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackPickAsset.class, "Your_Pick", null);
        return TrackPickAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json), new AssetExtraInfo<>(data));
    }
}
