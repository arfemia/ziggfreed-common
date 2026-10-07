package com.ziggfreed.common.shop.asset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.validation.Finding;

/** An offer family over a base the mod gate refused writes nothing and reports nothing; a typo still reports. */
class ShopGatedBaseTest {

    private static final ShopAssetStore STORE = ShopAssetStore.getInstance();

    @BeforeEach
    @AfterEach
    void emptyStore() {
        STORE.mergeEntries(Map.of());
        STORE.mergeGenerators(Map.of());
        ModGates.useProbeForTests(null);
    }

    private static ShopEntryGeneratorAsset generator(String id, String base) throws IOException {
        return generator(id, base, "{ \"Shop\": \"Harvest_Feast_Stall\" }");
    }

    private static ShopEntryGeneratorAsset generator(String id, String base, String child) throws IOException {
        return ShopEntryGeneratorAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(
                "{ \"Base\": \"" + base + "\", \"IdPattern\": \"" + base + "_{size}\","
                        + " \"ForEach\": [ { \"Token\": \"size\", \"Values\": [\"small\"] } ],"
                        + " \"Child\": " + child + " }"),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopEntryGeneratorAsset.class, id, null)));
    }

    @Test
    void aFamilyOverARefusedBaseWritesNothingAndSaysNothing() throws IOException {
        STORE.mergeEntries(Map.of(), Set.of("mmo_xp_packet"));
        STORE.mergeGenerators(Map.of("mmo_packets", generator("mmo_packets", "mmo_xp_packet")));

        ShopAssetStore.Resolution resolution = STORE.resolve(null);

        assertTrue(resolution.entries().isEmpty());
        assertFalse(resolution.issues().stream().map(Finding::code).anyMatch("UNKNOWN_BASE"::equals));
    }

    @Test
    void aBaseNobodyAuthoredIsStillReported() throws IOException {
        STORE.mergeEntries(Map.of(), Set.of("mmo_xp_packet"));
        STORE.mergeGenerators(Map.of("typo_packets", generator("typo_packets", "xp_pakcet")));

        assertTrue(STORE.resolve(null).issues().stream().map(Finding::code).anyMatch("UNKNOWN_BASE"::equals));
    }

    @Test
    void aGeneratedOfferWhoseOwnBodyNamesAnAbsentModIsDropped() throws IOException {
        ModGates.useProbeForTests(param -> param != null && param.trim().equals("Ziggfreed:MMOSkillTree") ? 0.0 : 1.0);
        STORE.mergeEntries(Map.of("xp_packet", CommerceFixtureSupport.entry(
                "{ \"Shop\": \"Harvest_Feast_Stall\" }", "Xp_Packet", null, null)));
        STORE.mergeGenerators(Map.of("packets", generator("packets", "xp_packet",
                "{ \"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
                        + " \"Param\": \"Ziggfreed:MMOSkillTree\", \"Min\": 1 } ] } }")));

        ShopAssetStore.Resolution resolution = STORE.resolve(null);

        assertFalse(resolution.entries().containsKey("xp_packet_small"), "a generated offer obeys the file rule");
        assertTrue(resolution.entries().containsKey("xp_packet"), "its base, ungated, stays on sale");
    }
}
