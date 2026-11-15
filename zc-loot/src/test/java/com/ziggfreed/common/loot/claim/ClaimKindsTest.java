package com.ziggfreed.common.loot.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Every payout kind the library names ships its word in the en-US {@code ziggfreedcommon.loot.lang}, since a kind
 * whose key is missing prints the raw key on every reward chest card of that kind; and a chest's own roll reads as the
 * boss's or as a bonus by its chest key.
 */
class ClaimKindsTest {

    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.loot.lang");

    /** The lang file's own namespace, which a key in the file leaves off. */
    private static final String NAMESPACE = "ziggfreedcommon.loot.";

    @Test
    void everyLibraryKindShipsItsWord() throws IOException, IllegalAccessException {
        Set<String> shipped = new HashSet<>();
        for (String line : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int equals = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && equals > 0) {
                shipped.add(NAMESPACE + trimmed.substring(0, equals).trim());
            }
        }
        List<String> kinds = new ArrayList<>();
        for (Field field : ClaimKinds.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && !field.getName().equals("KEY_PREFIX")) {
                kinds.add((String) field.get(null));
            }
        }
        assertFalse(kinds.isEmpty(), "the library names its kinds");
        for (String kind : kinds) {
            assertTrue(kind.startsWith(ClaimKinds.KEY_PREFIX), kind + " lives under the kinds' prefix");
            assertTrue(shipped.contains(kind), kind + " ships its word in " + ENGLISH.getFileName());
        }
        assertEquals(ClaimKinds.BOSS, ClaimKinds.kindOf("Boss:Zc_Encounter_Example"),
                "a source's site reads without regard to case");
        assertEquals(ClaimKinds.BOSS, ClaimKinds.kindOf(chestClaim("6f1c2b0e-0000-4000-8000-000000000001")),
                "a chest's own roll filed under a boss fight's run is the boss's");
        assertEquals(ClaimKinds.BONUS, ClaimKinds.kindOf(chestClaim("chest:6f1c2b0e-0000-4000-8000-000000000002")),
                "a chest's own roll under a kill's or a break's chest key is a bonus");
    }

    private static Claim chestClaim(String fight) {
        return new Claim("chest:x:player", "chest:zc_reward_chest", null, List.of(), null, List.of(), 0L, fight);
    }
}
