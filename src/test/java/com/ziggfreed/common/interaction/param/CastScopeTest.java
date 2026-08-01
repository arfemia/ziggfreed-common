package com.ziggfreed.common.interaction.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the immutable, builder-built {@link CastScope} carrier. No engine
 * touch anywhere in this class - pure value-object behavior.
 */
class CastScopeTest {

    @Test
    void defaults_areAllNullOrEmpty() {
        CastScope scope = CastScope.builder().build();
        assertNull(scope.scopeId());
        assertNull(scope.casterId());
        assertNull(scope.payload());
        assertNotNull(scope.vars());
        assertTrue(scope.vars().isEmpty());
    }

    @Test
    void builderFieldsRoundTrip() {
        UUID casterId = UUID.randomUUID();
        Object payload = new Object();
        CastScope scope = CastScope.builder()
                .scopeId("mmo:ability:fireball")
                .casterId(casterId)
                .payload(payload)
                .build();

        assertEquals("mmo:ability:fireball", scope.scopeId());
        assertEquals(casterId, scope.casterId());
        assertEquals(payload, scope.payload());
    }

    @Test
    void vars_isImmutable() {
        CastScope scope = CastScope.builder().var("mmo.school", "fire").build();
        assertEquals(1, scope.vars().size());
        assertThrows(UnsupportedOperationException.class, () -> scope.vars().put("x", "y"));
    }

    @Test
    void vars_isDefensiveCopy_mutatingSourceMapAfterBuildDoesNotLeak() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("mmo.school", "fire");
        CastScope scope = CastScope.builder().vars(source).build();

        source.put("mmo.school", "ice");
        source.put("mmo.extra", "added-after-build");

        assertEquals("fire", scope.vars().get("mmo.school"));
        assertFalse(scope.vars().containsKey("mmo.extra"));
    }

    @Test
    void var_nullOrBlankKey_isIgnored() {
        CastScope scope = CastScope.builder()
                .var(null, "value")
                .var("", "value")
                .var("   ", "value")
                .build();
        assertTrue(scope.vars().isEmpty());
    }

    @Test
    void var_nullValue_removesExistingEntry() {
        CastScope scope = CastScope.builder()
                .var("mmo.school", "fire")
                .var("mmo.school", null)
                .build();
        assertTrue(scope.vars().isEmpty());
    }

    @Test
    void vars_mergesRatherThanReplaces() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("a", "1");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("b", "2");

        CastScope scope = CastScope.builder().vars(first).vars(second).build();

        assertEquals("1", scope.vars().get("a"));
        assertEquals("2", scope.vars().get("b"));
        assertEquals(2, scope.vars().size());
    }

    @Test
    void vars_nullMapIsIgnored() {
        CastScope scope = CastScope.builder().var("a", "1").vars(null).build();
        assertEquals(1, scope.vars().size());
    }

    @Test
    void firedAtMillis_defaultsToNow() {
        long before = System.currentTimeMillis();
        CastScope scope = CastScope.builder().build();
        long after = System.currentTimeMillis();
        assertTrue(scope.firedAtMillis() >= before && scope.firedAtMillis() <= after);
    }

    @Test
    void firedAtMillis_explicitValueIsHonored() {
        CastScope scope = CastScope.builder().firedAtMillis(123L).build();
        assertEquals(123L, scope.firedAtMillis());
    }

    @Test
    void toString_isNonNull() {
        assertNotNull(CastScope.builder().build().toString());
        assertNotNull(CastScope.builder().scopeId("x").casterId(UUID.randomUUID()).payload("p").build().toString());
    }
}
