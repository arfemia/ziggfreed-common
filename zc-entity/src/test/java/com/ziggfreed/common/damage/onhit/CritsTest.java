package com.ziggfreed.common.damage.onhit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The on-hit stats' shipped surface: the crit pair and lifesteal are stat assets with tooltip labels, the crit
 * damage's Java fallback mirrors its asset's base, and the crit's word ships in en-US. No chance or damage value is
 * pinned: that is content a server retunes.
 */
class CritsTest {

    @Test
    void theStatsAreShippedAssetsWithATooltipLabel_andTheFallbackMirrorsTheCritDamageBase() throws IOException {
        String clientLang = read("/Server/Languages/en-US/client.lang");
        for (String id : List.of(Crits.CHANCE_STAT, Crits.DAMAGE_STAT, Lifesteal.STAT)) {
            JsonObject asset = JsonParser.parseString(read("/Server/Entity/Stats/" + id + ".json")).getAsJsonObject();
            assertTrue(asset.has("Max"), id + " has no Max");
            assertTrue(clientLang.contains("itemTooltip.stats." + id + " ="),
                    "en-US client.lang has no itemTooltip.stats." + id + " label");
        }
        JsonObject damage = JsonParser.parseString(
                read("/Server/Entity/Stats/" + Crits.DAMAGE_STAT + ".json")).getAsJsonObject();
        assertEquals(damage.get("Max").getAsDouble(), Crits.FALLBACK_DAMAGE_PERCENT, 0.0,
                "FALLBACK_DAMAGE_PERCENT mirrors the shipped CritDamage base: move the constant when you retune it");
        assertTrue(read("/Server/Languages/en-US/ziggfreedcommon.crit.lang").contains("combattext ="),
                "the crit's word is shipped in en-US");
    }

    private static String read(String path) throws IOException {
        try (InputStream in = CritsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
