package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.shop.ShopEngine;

/**
 * Every refusal an engine can answer with has a line a player can read, and that line exists.
 *
 * <p>The mapping is the one place a token turns into words, and it is exactly the thing that rots
 * silently: an engine grows a refusal, nothing maps it, and a player is told "you cannot do that
 * yet" about a daily limit they could have understood. So the completeness check DISCOVERS the
 * engines' constants by reflection rather than listing them, which is what makes a new token a
 * failing test rather than a quiet degrade.
 */
class CommerceRefusalsTest {

    /** The shipped English lines, which is where a key either exists or does not. */
    private static final String LANG = "/Server/Languages/en-US/ziggfreedcommon.commerce.lang";

    @Test
    @DisplayName("every engine refusal has a line of its own, not the generic locked one")
    void everyEngineTokenIsMapped() {
        List<String> unmapped = new ArrayList<>();
        for (String token : engineTokens()) {
            if (CommerceRefusals.KEY_LOCKED.equals(CommerceRefusals.keyOf(token))) {
                unmapped.add(token);
            }
        }
        assertTrue(unmapped.isEmpty(),
                "these engine refusals read as the generic locked line: " + unmapped);
    }

    @Test
    @DisplayName("every line a refusal can name is actually shipped in English")
    void everyMappedKeyExists() throws IOException {
        Set<String> keys = shippedKeys();
        List<String> missing = new ArrayList<>();
        for (String token : engineTokens()) {
            String key = CommerceRefusals.keyOf(token);
            if (!keys.contains(key)) {
                missing.add(key);
            }
        }
        if (!keys.contains(CommerceRefusals.KEY_LOCKED)) {
            missing.add(CommerceRefusals.KEY_LOCKED);
        }
        assertTrue(missing.isEmpty(), "these keys are referenced but not shipped: " + missing);
    }

    @Test
    @DisplayName("a shortfall names what the buyer is short of, so the line can say it")
    void aShortfallCarriesWhatItNamed() {
        CommerceRefusals.Refusal currency =
                CommerceRefusals.of(ShopEngine.REASON_SHORT_CURRENCY + "bounty_token");
        CommerceRefusals.Refusal item =
                CommerceRefusals.of(ShopEngine.REASON_SHORT_ITEM + "Iron_Ingot");

        assertEquals("bounty_token", currency.currencyId());
        assertNull(currency.itemId());
        assertEquals("Iron_Ingot", item.itemId());
        assertNull(item.currencyId());
        assertFalse(currency.isGeneric());
        assertFalse(item.isGeneric());
    }

    @Test
    @DisplayName("a gate refusal reads as the generic line rather than leaking what it gated on")
    void aGateRefusalStaysGeneric() {
        assertTrue(CommerceRefusals.of(GateEvaluator.REASON_FACTOR + "my_pack:veteran").isGeneric());
        assertTrue(CommerceRefusals.of(GateEvaluator.REASON_QUEST + "prologue").isGeneric());
        assertTrue(CommerceRefusals.of(GateEvaluator.REASON_PERMISSION).isGeneric());
        assertTrue(CommerceRefusals.of("something nothing here has ever heard of").isGeneric());
        assertTrue(CommerceRefusals.of(null).isGeneric());
    }

    @Test
    @DisplayName("both engines spell a reroll refusal the same way, so it maps once")
    void theTwoEnginesShareTheirRerollTokens() {
        assertEquals(ShopEngine.REASON_NO_REROLL, BoardEngine.REASON_NO_REROLL);
        assertEquals(ShopEngine.REASON_REROLL_CAP, BoardEngine.REASON_REROLL_CAP);
        assertEquals(ShopEngine.REASON_NO_ALTERNATIVE, BoardEngine.REASON_NO_ALTERNATIVE);
        assertEquals(ShopEngine.REASON_REROLL_CANNOT_PAY, BoardEngine.REASON_REROLL_CANNOT_PAY);
        assertEquals(CommerceRefusals.keyOf(ShopEngine.REASON_REROLL_CAP),
                CommerceRefusals.keyOf(BoardEngine.REASON_REROLL_CAP));
    }

    /**
     * Every {@code REASON_*} constant both engines declare, with a sample id appended to the ones
     * that are PREFIXES rather than whole tokens.
     */
    @Nonnull
    private static List<String> engineTokens() {
        List<String> out = new ArrayList<>();
        collectReasons(ShopEngine.class, out);
        collectReasons(BoardEngine.class, out);
        return out;
    }

    private static void collectReasons(@Nonnull Class<?> engine, @Nonnull List<String> out) {
        for (Field field : engine.getDeclaredFields()) {
            if (!field.getName().startsWith("REASON_")
                    || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                assertNotNull(value, field.getName());
                out.add(value.endsWith(":") ? value + "sample_id" : value);
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not read " + engine.getSimpleName() + "."
                        + field.getName(), e);
            }
        }
    }

    /** The keys the shipped English file actually defines. */
    @Nonnull
    private static Set<String> shippedKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        try (InputStream in = CommerceRefusalsTest.class.getResourceAsStream(LANG)) {
            assertNotNull(in, "the English commerce lang file is not on the test classpath");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
                    continue;
                }
                keys.add(trimmed.substring(0, equals).trim());
            }
        }
        return keys;
    }
}
