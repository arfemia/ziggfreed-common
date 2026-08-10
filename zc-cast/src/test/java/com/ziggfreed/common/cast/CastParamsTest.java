package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Coercion + fallback semantics of {@link CastParams}. */
class CastParamsTest {

    private static Map<String, Object> bag() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("num", 42);
        m.put("dbl", 3.5);
        m.put("str", "hello");
        m.put("flag", Boolean.TRUE);
        return m;
    }

    @Test
    void numberOrReadsANumberAndFallsBackAsDouble() {
        Map<String, Object> m = bag();
        assertEquals(42, CastParams.numberOr(m, "num", 7.0).intValue());
        assertEquals(3.5, CastParams.numberOr(m, "dbl", 7.0).doubleValue());
        // Missing key -> the fallback boxed as a Double.
        Number fb = CastParams.numberOr(m, "absent", 9.0);
        assertEquals(9.0, fb.doubleValue());
        assertTrue(fb instanceof Double, "fallback is boxed as Double");
        // Wrong-typed value -> fallback.
        assertEquals(9.0, CastParams.numberOr(m, "str", 9.0).doubleValue());
    }

    @Test
    void stringOrReadsAStringAndFallsBackIncludingNull() {
        Map<String, Object> m = bag();
        assertEquals("hello", CastParams.stringOr(m, "str", "fb"));
        assertEquals("fb", CastParams.stringOr(m, "absent", "fb"), "missing -> fallback");
        assertEquals("fb", CastParams.stringOr(m, "num", "fb"), "wrong type -> fallback");
        assertNull(CastParams.stringOr(m, "absent", null), "null fallback is honored");
    }

    @Test
    void boolOrReadsABooleanAndFallsBack() {
        Map<String, Object> m = bag();
        assertTrue(CastParams.boolOr(m, "flag", false));
        assertFalse(CastParams.boolOr(m, "absent", false), "missing -> fallback");
        assertTrue(CastParams.boolOr(m, "absent", true), "missing -> fallback (true)");
        assertTrue(CastParams.boolOr(m, "str", true), "wrong type -> fallback");
    }
}
