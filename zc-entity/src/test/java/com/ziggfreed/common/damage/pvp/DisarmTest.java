package com.ziggfreed.common.damage.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The bearer cannot attack ({@link Disarm}, R729): an effect tagged {@code ZigDisarm} disarms its wearer whatever the
 * tag's values, and refuses exactly the interaction types its values name, without regard to case; and a player's hit
 * on a player is cut by the victim's PvP stat, held at its cap ({@link PvpHits#through}).
 */
class DisarmTest {

    @Test
    void theTagDisarmsWhateverItsValues() {
        assertTrue(Disarm.carries(Map.of("ZigDisarm", new String[]{"Primary"}), null));
        assertTrue(Disarm.carries(Map.of("zigdisarm", new String[0]), null), "the tag alone stops every hit");
        assertFalse(Disarm.carries(Map.of("ZigEffectFamily", new String[]{"Disarmed"}), null));
        assertFalse(Disarm.carries(null, null));
    }

    @Test
    void theTagRefusesOnlyTheTypesItNames() {
        Map<String, String[]> flag = Map.of("ZigDisarm", new String[]{"Primary", " ability1 ", "Ability3"});

        assertTrue(Disarm.carries(flag, "Primary"));
        assertTrue(Disarm.carries(flag, "Ability1"), "any casing, trimmed");
        assertFalse(Disarm.carries(flag, "Use"), "a Use runs as ever");
        assertFalse(Disarm.carries(flag, "Secondary"), "a type the tag does not name runs");
        assertFalse(Disarm.carries(Map.of("ZigDisarm", new String[0]), "Primary"),
                "the tag with no value refuses no chain");
    }

    @Test
    void aPlayersHitIsCutByTheVictimsStatHeldAtItsCap() {
        assertEquals(1.0, PvpHits.through(0.0, 25.0), 1e-9);
        assertEquals(0.9, PvpHits.through(10.0, 25.0), 1e-9);
        assertEquals(0.75, PvpHits.through(60.0, 25.0), 1e-9, "held at the cap");
        assertEquals(1.0, PvpHits.through(-20.0, 25.0), 1e-9, "a total below 0 cuts nothing");
        assertEquals(1.0, PvpHits.through(Double.NaN, 25.0), 1e-9);
        assertEquals(0.0, PvpHits.through(150.0, Double.NaN), 1e-9, "never below nothing");
    }
}
