package com.ziggfreed.common.damage.weakspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.grant.Placed;

/**
 * Weak spots' shipped surface and the arithmetic every hit rests on. The two stats ship with tooltip labels and the
 * ceiling's Java fallback mirrors its asset; a weak spot pays once, never past its ceiling, and never stacks on the
 * headshot vanilla's own weapon already paid (the larger of the two); the effect tag's Owner value reads as
 * owner-only; a grant held more often widens further. No ceiling or
 * bonus value is pinned: that is content a server retunes.
 */
class WeakSpotsTest {

    @Test
    void bothStatsAreShippedAssetsWithATooltipLabel_andTheFallbackMirrorsTheCeiling() throws IOException {
        String clientLang = read("/Server/Languages/en-US/client.lang");
        for (String id : List.of(WeakSpots.DAMAGE_STAT, WeakSpots.CEILING_STAT)) {
            JsonObject asset = JsonParser.parseString(read("/Server/Entity/Stats/" + id + ".json")).getAsJsonObject();
            assertTrue(asset.has("Max"), id + " has no Max");
            assertTrue(clientLang.contains("itemTooltip.stats." + id + " ="),
                    "en-US client.lang has no itemTooltip.stats." + id + " label");
        }
        JsonObject ceiling = JsonParser.parseString(
                read("/Server/Entity/Stats/" + WeakSpots.CEILING_STAT + ".json")).getAsJsonObject();
        assertEquals(ceiling.get("Max").getAsDouble(), WeakSpots.FALLBACK_CEILING_PERCENT, 0.0,
                "FALLBACK_CEILING_PERCENT mirrors the shipped WeakSpotMax Max: move the constant when you retune it");
        assertTrue(read("/Server/Languages/en-US/ziggfreedcommon.weakspot.lang").contains("combattext ="),
                "the combat text's key is shipped in en-US");
    }

    @Test
    void aWeakSpotPaysOnce_heldToItsCeiling() {
        assertEquals(0.5, WeakSpots.bonusFraction(50.0, 75.0), 1e-9);
        assertEquals(0.75, WeakSpots.bonusFraction(300.0, 75.0), 1e-9, "never past the ceiling");
        assertEquals(0.0, WeakSpots.bonusFraction(-20.0, 75.0), 1e-9, "a negative bonus takes nothing away");
        assertEquals(0.0, WeakSpots.bonusFraction(40.0, -10.0), 1e-9, "a ceiling under 0 pays nothing");
    }

    @Test
    void aVanillaHeadshotOrBackstabAndOursNeverStack_theLargerPays() {
        // Ours +75% on a hit vanilla already paid +30% for: the hit ends at 1.75 times a body hit, never 1.3 * 1.75.
        assertEquals(1.75 / 1.3, WeakSpots.factorOver(0.75, 0.3), 1e-9);
        assertEquals(1.0, WeakSpots.factorOver(0.2, 0.3), 1e-9, "vanilla's larger headshot stands alone");
        assertEquals(1.5, WeakSpots.factorOver(0.5, 0.0), 1e-9, "with no vanilla headshot ours pays whole");
        assertEquals(0.3, VanillaWeakSpots.share(1.3, 1.0), 1e-9, "a head branch's share over its body's");
        assertEquals(0.0, VanillaWeakSpots.share(30.0, 37.0), 1e-9, "a weaker head branch pays nothing extra");
        // A dagger's backstab branch (Angle 180, AngleDistance 80) is netted out like a headshot; a front arc is not.
        assertTrue(VanillaWeakSpots.coversBehind(Math.PI, Math.toRadians(80)));
        assertFalse(VanillaWeakSpots.coversBehind(0.0, Math.toRadians(80)));
    }

    @Test
    void theTagsOwnerValueOpensTheWindowForTheOwnerAlone() {
        assertEquals(WeakSpotWindows.OWNER,
                WeakSpotWindows.kindOf(Map.of(WeakSpots.TAG, new String[] {"Owner"}), false));
        assertEquals(WeakSpotWindows.ANYONE,
                WeakSpotWindows.kindOf(Map.of(WeakSpots.TAG, new String[] {"Anyone"}), false));
        assertEquals(WeakSpotWindows.ANYONE, WeakSpotWindows.kindOf(Map.of(WeakSpots.TAG, new String[0]), false));
        assertEquals(WeakSpotWindows.ANYONE, WeakSpotWindows.kindOf(Map.of(), true), "a listed effect opens it");
        assertEquals(WeakSpotWindows.NONE, WeakSpotWindows.kindOf(Map.of("Other", new String[0]), false));
    }

    @Test
    void aGrantHeldMoreOftenWidensFurther_upToAWholeHealthBar() {
        Map<String, WeakSpotGrantWriter.Rules> wanted = WeakSpotGrantWriter.wanted(List.of(
                new Placed("zigtrack:daggers:coup", WeakSpotGrant.of(8.0), 3),
                new Placed("zigtrack:archery:pin", WeakSpotGrant.of(null, "Root"), 1)));
        assertEquals(24.0, wanted.get("zigtrack:daggers:coup").healthBelow(), 1e-9);
        assertTrue(wanted.get("zigtrack:archery:pin").effects().contains("root"));
        WeakSpotGrantWriter.Rules folded = WeakSpotGrantWriter.fold(List.of(
                new WeakSpotGrantWriter.Rules(80.0, Set.of()),
                new WeakSpotGrantWriter.Rules(50.0, Set.of("root"))));
        assertEquals(100.0, folded.healthBelow(), 1e-9, "never past a whole health bar");
        assertTrue(WeakSpotGrantWriter.wanted(List.of(new Placed("zigtrack:x:y", WeakSpotGrant.of(0.0), 1))).isEmpty(),
                "a grant asking nothing writes nothing");
    }

    private static String read(String path) throws IOException {
        try (InputStream in = WeakSpotsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
