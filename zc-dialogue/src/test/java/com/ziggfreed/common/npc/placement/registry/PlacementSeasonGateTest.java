package com.ziggfreed.common.npc.placement.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementValidator;
import com.ziggfreed.common.npc.placement.registry.PlacementGate.GateVerdict;
import com.ziggfreed.common.season.SeasonGate;

/**
 * A placement's {@code Season} is a built-in gate of the live chain: out of season it denies with its
 * own reason, ahead of {@code Requires}; on the next sweep after the season starts the same asset
 * stands, which is all the calendar's forced start and end sweeps need; a child keeps its base's
 * season whatever it writes in {@code Requires}; and the full audit names an unknown id.
 */
class PlacementSeasonGateTest {

    private AtomicBoolean harvestLive;

    @BeforeEach
    void declareTheCalendarsSwitch() {
        harvestLive = new AtomicBoolean(false);
        FeatureFlags.register(SeasonGate.NAMESPACE, "Harvest_Feast_Live", "test", harvestLive::get);
    }

    @AfterEach
    void forget() {
        FeatureFlags.reset();
    }

    private static NpcPlacementAsset decode(String id, String json, @Nullable String parentId,
            @Nullable NpcPlacementAsset parent) throws IOException {
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(NpcPlacementAsset.class, id, parentId)));
    }

    @Test
    void aSeasonalPlacementIsDeniedOffSeasonAndStandsOnTheNextSweepInSeason() throws IOException {
        NpcPlacementAsset host = decode("Harvest_Feast_Host",
                "{ \"Season\": \"Harvest_Feast\", \"Identity\": { \"Role\": \"Harvest_Feast_Host\" } }", null, null);

        GateVerdict off = PlacementGates.decide(host, null, null);
        assertTrue(off.isDenied());
        assertEquals(PlacementGates.REASON_SEASON, off.reasonKey());

        harvestLive.set(true);

        assertFalse(PlacementGates.decide(host, null, null).isDenied(),
                "the chain reads the season afresh, so the calendar's forced start sweep places it");
    }

    @Test
    void theSeasonIsTheReasonAListingNamesAheadOfRequires() throws IOException {
        NpcPlacementAsset host = decode("Harvest_Feast_Host", "{ \"Season\": \"Harvest_Feast\","
                + " \"Requires\": { \"Factors\": [ { \"Factor\": \"nobody_registered:standing\", \"Min\": 1 } ] } }",
                null, null);

        assertEquals(PlacementGates.REASON_SEASON, PlacementGates.decide(host, null, null).reasonKey());
        harvestLive.set(true);
        assertEquals(PlacementGates.REASON_REQUIRES, PlacementGates.decide(host, null, null).reasonKey(),
                "in season the placement answers to its own Requires as before");
    }

    @Test
    void aChildKeepsItsBasesSeasonWhateverItWritesInRequires() throws IOException {
        NpcPlacementAsset base = decode("Harvest_Feast_Base", "{ \"Season\": \"Harvest_Feast\" }", null, null);
        NpcPlacementAsset child = decode("Harvest_Feast_Host",
                "{ \"Requires\": { \"Factors\": [ { \"Factor\": \"yourmod:standing\", \"Min\": 1 } ] } }",
                "Harvest_Feast_Base", base);

        assertEquals("Harvest_Feast", child.getSeason());
        assertEquals(PlacementGates.REASON_SEASON, PlacementGates.decide(child, null, null).reasonKey());
    }

    @Test
    void theFullAuditNamesASeasonNoEventDeclares() throws IOException {
        NpcPlacementAsset host = decode("Harvest_Feast_Host",
                "{ \"Season\": \"Harvest_Faest\", \"Identity\": { \"Role\": \"Harvest_Feast_Host\" } }", null, null);

        assertTrue(NpcPlacementValidator.audit(host).stream()
                        .anyMatch(f -> SeasonGate.UNKNOWN_SEASON.equals(f.code())),
                "an unknown id is a warning, and the placement stays down");
    }
}
