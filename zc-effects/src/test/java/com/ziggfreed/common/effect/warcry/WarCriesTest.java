package com.ziggfreed.common.effect.warcry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A war cry read off an effect's own tags ({@link WarCries#of}): the tag names the rally, every number left out is the
 * game's own goblin war cry's, a number that does not read keeps its default, and a range comes out in order.
 * Untagged: nothing here builds an engine item.
 */
class WarCriesTest {

    @Test
    void anEffectWithoutTheTagIsNoWarCry() {
        assertNull(WarCries.of(null));
        assertNull(WarCries.of(Map.of("ZigEffectFamily", new String[] {"Stunned"})));
        assertNull(WarCries.of(Map.of("ZigWarCry", new String[] {" "})), "a blank rally names nothing");
    }

    @Test
    void theTagAloneTakesTheGamesOwnNumbers() {
        WarCries.Cry cry = WarCries.of(Map.of("zigwarcry", new String[] {" Zig_Rallied "}));
        assertNotNull(cry, "the key is matched without regard to case");
        assertEquals(new WarCries.Cry("Zig_Rallied", null, 10.0, 18.0, 25.0, 1.0), cry);
    }

    @Test
    void everyNumberReadsAndARangeComesOutInOrder() {
        WarCries.Cry cry = WarCries.of(Map.of(
                "ZigWarCry", new String[] {"MyPack_Rallied"},
                "ZigWarCryShout", new String[] {"MyPack_Shout"},
                "ZigWarCryRadius", new String[] {"12.5"},
                "ZigWarCryEvery", new String[] {"30", "20"},
                "ZigWarCryDelay", new String[] {"0.5"}));
        assertEquals(new WarCries.Cry("MyPack_Rallied", "MyPack_Shout", 12.5, 20.0, 30.0, 0.5), cry);

        WarCries.Cry odd = WarCries.of(Map.of(
                "ZigWarCry", new String[] {"MyPack_Rallied"},
                "ZigWarCryRadius", new String[] {"far"},
                "ZigWarCryEvery", new String[] {"15"}));
        assertNotNull(odd);
        assertEquals(10.0, odd.radius(), "a number that does not read keeps its default");
        assertEquals(15.0, odd.everyMin());
        assertEquals(15.0, odd.everyMax(), "one number is the whole range");
    }
}
