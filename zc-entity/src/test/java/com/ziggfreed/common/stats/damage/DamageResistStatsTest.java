package com.ziggfreed.common.stats.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The resistance stat family's shipped surface: every {@code DamageResist*} stat this module ships (each type's,
 * the every-type stat, the conditional ones and the ceiling) carries an item-tooltip label in its en-US
 * {@code client.lang} (the file every other locale falls back to per key), since a stat with no asset
 * drops the item naming it from nothing and a stat with no label prints its raw id on a tooltip. A type
 * stat starts at nothing, so an unmodified player resists nothing. No ceiling value is pinned: that is
 * content a server retunes.
 */
class DamageResistStatsTest {

    @Test
    void everyResistanceStat_isAShippedAssetWithATooltipLabel() throws IOException, URISyntaxException {
        String clientLang = read("/Server/Languages/en-US/client.lang");
        List<String> ids = DamageBonusStatsTest.shippedStatIds("DamageResist");
        assertTrue(ids.contains(DamageResistances.EVERY_TYPE_STAT_ID), "the every-type stat ships");
        assertTrue(ids.contains(DamageResistances.CEILING_STAT_ID), "the ceiling ships");
        assertTrue(ids.contains(DamageResistances.PREFIX + DamageResistances.FALL), "the resist-only Fall ships");
        for (String id : ids) {
            JsonObject asset = JsonParser.parseString(read("/Server/Entity/Stats/" + id + ".json")).getAsJsonObject();
            assertTrue(asset.has("Max"), id + " has no Max");
            assertTrue(clientLang.contains("itemTooltip.stats." + id + " ="),
                    "en-US client.lang has no itemTooltip.stats." + id + " label");
            if (!id.equals(DamageResistances.CEILING_STAT_ID)) {
                assertEquals(0.0, asset.get("Max").getAsDouble(), 0.0, id + " base Max");
            }
        }
    }

    /**
     * No ceiling magnitude is pinned here: the shipped file is read and compared, so retuning the asset
     * is retuning the constant beside it, never this test. The point of the mirror is that a failed load
     * fails closed toward the shipped bound.
     */
    @Test
    void fallbackCeiling_mirrorsTheShippedAssetsMax_andStandsInWithoutOne() throws IOException {
        JsonObject shipped = JsonParser.parseString(
                read("/Server/Entity/Stats/" + DamageResistances.CEILING_STAT_ID + ".json")).getAsJsonObject();
        assertEquals(Math.round(shipped.get("Max").getAsDouble()), DamageResistances.FALLBACK_CEILING_PERCENT,
                "FALLBACK_CEILING_PERCENT mirrors the shipped DamageResistMax Max: move the constant when you retune the asset");
        assertEquals(DamageResistances.FALLBACK_CEILING_PERCENT, DamageResistances.assetCeilingPercent(), 0.0,
                "no asset store in this JVM: the fallback stands in, never a whole hit");
    }

    private static String read(String path) throws IOException {
        try (InputStream in = DamageResistStatsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
