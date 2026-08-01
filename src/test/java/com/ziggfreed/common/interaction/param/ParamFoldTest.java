package com.ziggfreed.common.interaction.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link ParamFold}'s guard rail. No engine touch: store/caster stay
 * null throughout, only the resolver's own arithmetic is exercised.
 */
class ParamFoldTest {

    @Test
    void noResolver_returnsBase() {
        ParamFold fold = new ParamFold();
        assertFalse(fold.hasResolver());
        assertNull(fold.getResolver());

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(10.0).build());
        assertEquals(10.0, result);
    }

    @Test
    void nullRequest_returnsZero() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> 999.0);
        assertEquals(0.0, fold.resolve((ParamFoldRequest) null));
    }

    @Test
    void blankParamKey_returnsBaseWithoutCallingResolver() {
        ParamFold fold = new ParamFold();
        boolean[] called = {false};
        fold.setResolver(req -> {
            called[0] = true;
            return 999.0;
        });

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("").base(5.0).build());

        assertEquals(5.0, result);
        assertFalse(called[0], "a blank paramKey must never invoke the resolver");
    }

    @Test
    void nullParamKeyNormalizedToBlank_returnsBaseWithoutCallingResolver() {
        ParamFold fold = new ParamFold();
        boolean[] called = {false};
        fold.setResolver(req -> {
            called[0] = true;
            return 999.0;
        });

        double result = fold.resolve(ParamFoldRequest.builder().paramKey(null).base(5.0).build());

        assertEquals(5.0, result);
        assertFalse(called[0]);
    }

    @Test
    void resolverThrows_returnsBase_doesNotPropagate() {
        ParamFold fold = new ParamFold("test-label");
        fold.setResolver(req -> {
            throw new IllegalStateException("boom");
        });

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(7.0).build());

        assertEquals(7.0, result);
    }

    @Test
    void resolverReturnsNaN_returnsBase() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> Double.NaN);

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(3.0).build());

        assertEquals(3.0, result);
    }

    @Test
    void resolverReturnsPositiveInfinity_returnsBase() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> Double.POSITIVE_INFINITY);

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(3.0).build());

        assertEquals(3.0, result);
    }

    @Test
    void resolverReturnsNegativeInfinity_returnsBase() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> Double.NEGATIVE_INFINITY);

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(3.0).build());

        assertEquals(3.0, result);
    }

    @Test
    void resolverReturnsFiniteValue_isUsed() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> req.base() * 2.0);

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(4.0).build());

        assertEquals(8.0, result);
    }

    @Test
    void setResolverNull_restoresIdentity() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> 999.0);
        assertTrue(fold.hasResolver());

        fold.setResolver(null);

        assertFalse(fold.hasResolver());
        assertNull(fold.getResolver());
        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(11.0).build());
        assertEquals(11.0, result);
    }

    @Test
    void setResolver_lastWriteWins() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> 1.0);
        fold.setResolver(req -> 2.0);

        double result = fold.resolve(ParamFoldRequest.builder().paramKey("damage").base(0.0).build());

        assertEquals(2.0, result);
    }

    @Test
    void convenienceOverload_matchesRequestOverload() {
        ParamFold fold = new ParamFold();
        fold.setResolver(req -> req.base() + 100.0);

        double viaRequest = fold.resolve(
                ParamFoldRequest.builder().store(null).caster(null).scope(null).paramKey("damage").base(5.0).build());
        double viaConvenience = fold.resolve(null, null, null, "damage", 5.0);

        assertEquals(viaRequest, viaConvenience);
        assertEquals(105.0, viaConvenience);
    }
}
