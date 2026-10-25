package com.ziggfreed.common.stats.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The damage bonus stat family's shipped surface: every {@code DamageBonus*} stat this module ships (each type's
 * pair, the every-type stat and the ceiling, the files the damage types are read off) carries an item-tooltip
 * label in its en-US {@code client.lang}; the ceiling's Java fallback mirrors the shipped
 * asset. And the two pure pieces the hit's arithmetic rests on: adding into the engine's sum, and a route's
 * share of the ceiling. No ceiling value is pinned: that is content a server retunes.
 */
class DamageBonusStatsTest {

    @Test
    void everyBonusStat_isAShippedAssetWithATooltipLabel() throws IOException, URISyntaxException {
        String clientLang = read("/Server/Languages/en-US/client.lang");
        List<String> ids = shippedStatIds("DamageBonus");
        assertTrue(ids.contains(TypedDamageBonuses.EVERY_TYPE_STAT_ID), "the every-type stat ships");
        assertTrue(ids.contains(TypedDamageBonuses.CEILING_STAT_ID), "the ceiling ships");
        assertTrue(ids.stream().anyMatch(id -> id.startsWith(TypedDamageBonuses.PERCENT_PREFIX)),
                "the damage types are read off shipped DamageBonus_<Type> files");
        for (String id : ids) {
            JsonObject asset = JsonParser.parseString(read("/Server/Entity/Stats/" + id + ".json")).getAsJsonObject();
            assertTrue(asset.has("Max"), id + " has no Max");
            assertTrue(clientLang.contains("itemTooltip.stats." + id + " ="),
                    "en-US client.lang has no itemTooltip.stats." + id + " label");
        }
    }

    @Test
    void fallbackCeiling_mirrorsTheShippedAssetsMax_andStandsInWithoutOne() throws IOException {
        JsonObject shipped = JsonParser.parseString(
                read("/Server/Entity/Stats/" + TypedDamageBonuses.CEILING_STAT_ID + ".json")).getAsJsonObject();
        assertEquals(Math.round(shipped.get("Max").getAsDouble()), TypedDamageBonuses.FALLBACK_CEILING_PERCENT,
                "FALLBACK_CEILING_PERCENT mirrors the shipped DamageBonusMax Max: move the constant when you retune the asset");
        assertEquals(TypedDamageBonuses.FALLBACK_CEILING_PERCENT, TypedDamageBonuses.assetCeilingPercent(), 0.0,
                "no asset store in this JVM: the fallback stands in");
    }

    @Test
    void addedToSum_isAdditiveWithTheEnginesSum_neverASecondMultiply() {
        // base 10, engine sum +100% (Enrage plus a line): the hit arrives at 20. A further +50% joins the sum:
        // 10 * (1 + 1.0 + 0.5) = 25, never 20 * 1.5 = 30.
        assertEquals(25.0, NativeOutgoing.addedToSum(20.0, 1.0, 0.5, 0.0), 1e-9);
        // A flat lands inside the sum, as the engine's own flats do: (10 + 4) * 2.5 = 35.
        assertEquals(35.0, NativeOutgoing.addedToSum(20.0, 1.0, 0.5, 4.0), 1e-9);
        assertEquals(20.0, NativeOutgoing.addedToSum(20.0, 1.0, 0.0, 0.0), 1e-9);
        // Two additions in turn compose to one: 10 * (1 + 1.0 + 0.2 + 0.3).
        double once = NativeOutgoing.addedToSum(20.0, 1.0, 0.2, 0.0);
        assertEquals(25.0, NativeOutgoing.addedToSum(once, 1.2, 0.3, 0.0), 1e-9);
        assertEquals(0.0, NativeOutgoing.addedToSum(0.0, -1.0, 0.5, 0.0), 1e-9,
                "a hit the sum already took to nothing has nothing left to rescale");
    }

    @Test
    void heldToCeiling_givesARouteOnlyTheHeadroomTheNativeLinesLeft() {
        assertEquals(50.0, TypedDamageBonuses.heldToCeiling(100.0, 50.0, 1000.0), 1e-9);
        assertEquals(20.0, TypedDamageBonuses.heldToCeiling(980.0, 50.0, 1000.0), 1e-9);
        assertEquals(0.0, TypedDamageBonuses.heldToCeiling(1200.0, 50.0, 1000.0), 1e-9);
        assertEquals(-100.0, TypedDamageBonuses.heldToCeiling(0.0, -300.0, 1000.0), 1e-9,
                "no route takes a hit below a whole hit off");
    }

    /** Every shipped stat file's id under {@code Server/Entity/Stats/} that starts with {@code prefix}, sorted. */
    static List<String> shippedStatIds(String prefix) throws IOException, URISyntaxException {
        URL dir = DamageBonusStatsTest.class.getResource("/Server/Entity/Stats");
        assertNotNull(dir, "missing classpath folder /Server/Entity/Stats");
        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json") && name.startsWith(prefix)) {
                    ids.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        }
        ids.sort(null);
        return ids;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = DamageBonusStatsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
