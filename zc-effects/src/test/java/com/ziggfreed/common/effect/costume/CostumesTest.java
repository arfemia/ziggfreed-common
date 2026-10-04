package com.ziggfreed.common.effect.costume;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The slice of {@link Costumes} a unit JVM can reach: every guard runs before the engine's effect
 * asset map is touched, so nobody to dress is answered without one. The dress and take-off paths
 * themselves are the in-game smoke's.
 */
class CostumesTest {

    private static final ComponentAccessor<EntityStore> NO_ACCESSOR = null;
    private static final Ref<EntityStore> NO_WEARER = null;

    @Test
    void nobodyToDressIsNeverDressed() {
        assertEquals(Costumes.DressOutcome.CANNOT_WEAR, Costumes.dress(NO_ACCESSOR, NO_WEARER, "Yourpack_Costume"));
    }

    @Test
    void aBlankEffectDressesNobody() {
        assertEquals(Costumes.DressOutcome.CANNOT_WEAR, Costumes.dress(NO_ACCESSOR, NO_WEARER, "  "));
        assertEquals(Costumes.DressOutcome.CANNOT_WEAR, Costumes.dress(NO_ACCESSOR, NO_WEARER, null));
    }

    @Test
    void nobodyToUndressTakesNothingOff() {
        assertEquals(0, Costumes.takeOff(NO_ACCESSOR, NO_WEARER));
    }
}
