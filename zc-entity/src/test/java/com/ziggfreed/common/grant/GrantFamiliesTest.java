package com.ziggfreed.common.grant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A grant's families are its stat's own {@code ZigGrantFamily} tag (R858), read as the engine hands raw tags over:
 * every value in written order, blanks left out, and nothing for a stat that writes no tag.
 */
class GrantFamiliesTest {

    @Test
    void theTagNamesTheFamilies() {
        Map<String, String[]> raw = Map.of(GrantFamilies.TAG, new String[] {"Combat", " ", " Pvp "},
                "Combat", new String[0], GrantFamilies.TAG + "=Combat", new String[0]);
        assertEquals(List.of("Combat", "Pvp"), GrantFamilies.read(raw),
                "written order, trimmed, blanks out; the engine's expanded tags are not families");
    }

    @Test
    void noTagNamesNoFamily() {
        assertTrue(GrantFamilies.read(null).isEmpty());
        assertTrue(GrantFamilies.read(Map.of("Family", new String[] {"Combat"})).isEmpty(), "another tag says nothing");
        assertTrue(GrantFamilies.read(Map.of(GrantFamilies.TAG, new String[0])).isEmpty());
    }
}
