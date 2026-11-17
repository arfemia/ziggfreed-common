package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageClass;

/**
 * The class a hit reads as for a {@code Hit.Classes} read ({@link HitReads#classFor}, {@link HitReads#classTag}), which
 * has bitten twice: the engine gives a damage that names no class {@code UNKNOWN}, never null, so a read that waited
 * for null never let an attacker's {@code ZigHitClass} tag class the game's held axe swing. A classless hit takes the
 * tag's class, a classed hit keeps its own, a hit with neither has none, and a damage-over-time tick is never classed.
 */
class HitClassDecisionTest {

    private static Supplier<String> tagged(String value) {
        return () -> value;
    }

    @Test
    void aClasslessHit_takesTheTaggedClass() {
        assertEquals("charged", HitReads.classFor(true, false, DamageClass.UNKNOWN, tagged("charged")),
                "the engine's UNKNOWN is no class, so the attacker's tag classes the hit");
        assertEquals("charged", HitReads.classFor(true, false, null, tagged("charged")),
                "a calculator naming nothing at all reads the same way");
    }

    @Test
    void aClassedHit_keepsItsOwnClass_andNeverAsksTheTag() {
        AtomicBoolean asked = new AtomicBoolean();
        Supplier<String> tag = () -> {
            asked.set(true);
            return "charged";
        };
        assertEquals("light", HitReads.classFor(true, false, DamageClass.LIGHT, tag));
        assertEquals("signature", HitReads.classFor(true, false, DamageClass.SIGNATURE, tag));
        assertFalse(asked.get(), "a hit with its own class never reads its attacker's effects");
    }

    @Test
    void aClasslessHit_withNoTag_hasNoClass() {
        assertNull(HitReads.classFor(true, false, DamageClass.UNKNOWN, tagged(null)),
                "UNKNOWN never reads as a class name of its own");
    }

    @Test
    void aDamageOverTimeTick_isNeverClassed() {
        assertNull(HitReads.classFor(false, true, null, tagged("charged")), "a tick carries no calculator");
        assertNull(HitReads.classFor(true, true, DamageClass.CHARGED, tagged("charged")),
                "an over-time hit is never classed, even with a calculator");
        assertNull(HitReads.classFor(false, false, null, tagged("charged")),
                "a hit with no calculator (a proc's own strike) takes no tag");
    }

    @Test
    void theTagReadsItsFirstValue_lowerCased_underAnyCasingOfItsKey() {
        assertEquals("charged", HitReads.classTag(Map.of("ZigHitClass", new String[] {"Charged"})));
        assertEquals("light", HitReads.classTag(Map.of("zighitclass", new String[] {" ", "Light"})));
        assertNull(HitReads.classTag(Map.of("ZigWeakSpot", new String[] {"Charged"})), "another tag gives no class");
        assertNull(HitReads.classTag(null));
    }
}
