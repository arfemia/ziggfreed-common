package com.ziggfreed.common.progress.gate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.season.SeasonGate;

/**
 * The one presence read: {@code Enabled}, the {@code Season} and every lifted condition, all live; and
 * {@code isLive}, which tells a fold when its engine needs a supplier rather than a constant. The
 * feature namespace besides the calendar's is unique to this class.
 */
class FeatureLiftPresentTest {

    private AtomicBoolean harvestLive;
    private AtomicBoolean trading;

    @BeforeEach
    void declare() {
        harvestLive = new AtomicBoolean(true);
        trading = new AtomicBoolean(true);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
        FeatureFlags.register("liftpresent_gate", "trading", "yourmod", trading::get);
    }

    @AfterEach
    void forget() {
        FeatureFlags.reset();
    }

    private static List<FeatureLift.Lifted> tradingLifted() throws IOException {
        GateSpec gate = GateSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{ \"Factors\": [ { \"Factor\": \"liftpresent_gate:feature\", \"Param\": \"Trading\", \"Min\": 1 } ] }"),
                new ExtraInfo());
        return FeatureLift.liftKnown(gate).lifted();
    }

    @Test
    void presentIsEnabledAndTheSeasonAndEveryLiftedConditionReadNow() throws IOException {
        List<FeatureLift.Lifted> lifted = tradingLifted();

        assertTrue(FeatureLift.present(true, "Harvest_Feast", lifted));
        harvestLive.set(false);
        assertFalse(FeatureLift.present(true, "Harvest_Feast", lifted), "out of season is absent");
        assertTrue(FeatureLift.present(true, null, lifted), "no season is all year");
        harvestLive.set(true);
        trading.set(false);
        assertFalse(FeatureLift.present(true, "Harvest_Feast", lifted), "a lifted condition still hides");
        trading.set(true);
        assertFalse(FeatureLift.present(false, "Harvest_Feast", lifted), "Enabled false wins whatever the rest says");
    }

    @Test
    void onlyASeasonOrALiftedConditionMakesPresenceLive() throws IOException {
        assertFalse(FeatureLift.isLive(null, List.of()), "nothing can move, so a fold hands its engine a constant");
        assertFalse(FeatureLift.isLive("  ", List.of()));
        assertTrue(FeatureLift.isLive("Harvest_Feast", List.of()));
        assertTrue(FeatureLift.isLive(null, tradingLifted()));
    }
}
