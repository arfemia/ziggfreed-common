package com.ziggfreed.common.interaction.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link ParamSlot}. No engine touch beyond {@link ParamSlot#codec()}
 * itself, which mirrors the plain structured-{@code BuilderCodec} pattern the MMO jar's own
 * {@code AssetCodecInitTest} (and this jar's {@code asset.AssetCodecInitTest}) already force
 * static-init in a unit JVM without a live server - safe because {@code ParamSlot}'s codec is a
 * self-contained value type (no {@code Interaction} subclassing, no validators).
 */
class ParamSlotTest {

    @Test
    void defaults() {
        ParamSlot slot = new ParamSlot();
        assertNull(slot.getKey());
        assertEquals(0.0, slot.getBase());
    }

    @Test
    void ctor_setsFields() {
        ParamSlot slot = new ParamSlot("damage", 12.0);
        assertEquals("damage", slot.getKey());
        assertEquals(12.0, slot.getBase());
    }

    @Test
    void resolve_nullFold_returnsBase() {
        ParamSlot slot = new ParamSlot("damage", 12.0);
        assertEquals(12.0, slot.resolve(null, null, null, null));
    }

    @Test
    void resolve_nullKey_returnsBaseWithoutTouchingFold() {
        ParamSlot slot = new ParamSlot(null, 12.0);
        ParamFold fold = new ParamFold();
        boolean[] called = {false};
        fold.setResolver(req -> {
            called[0] = true;
            return 999.0;
        });

        double result = slot.resolve(fold, null, null, null);

        assertEquals(12.0, result);
        assertFalse(called[0]);
    }

    @Test
    void resolve_blankKey_returnsBaseWithoutTouchingFold() {
        ParamSlot slot = new ParamSlot("   ", 12.0);
        ParamFold fold = new ParamFold();
        boolean[] called = {false};
        fold.setResolver(req -> {
            called[0] = true;
            return 999.0;
        });

        double result = slot.resolve(fold, null, null, null);

        assertEquals(12.0, result);
        assertFalse(called[0]);
    }

    @Test
    void resolve_withKeyAndFold_routesThroughFoldWithReceivedKeyAndBase() {
        ParamSlot slot = new ParamSlot("damage", 12.0);
        ParamFold fold = new ParamFold();
        String[] receivedKey = {null};
        double[] receivedBase = {-1.0};
        fold.setResolver(req -> {
            receivedKey[0] = req.paramKey();
            receivedBase[0] = req.base();
            return req.base() * 2.0;
        });

        double result = slot.resolve(fold, null, null, null);

        assertEquals("damage", receivedKey[0]);
        assertEquals(12.0, receivedBase[0]);
        assertEquals(24.0, result);
    }

    @Test
    void codec_isNonNullAndMemoized() {
        assertNotNull(ParamSlot.codec());
        assertSame(ParamSlot.codec(), ParamSlot.codec(), "codec() must return the same memoized instance");
    }
}
