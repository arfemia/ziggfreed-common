package com.ziggfreed.common.interaction.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link ParamFoldRequest}'s builder projections and normalization.
 * Pure value-object behavior, no engine touch (store/caster are left null throughout - only
 * their pass-through identity matters here).
 */
class ParamFoldRequestTest {

    @Test
    void defaults() {
        ParamFoldRequest request = ParamFoldRequest.builder().build();
        assertNull(request.store());
        assertNull(request.caster());
        assertNull(request.scopeId());
        assertEquals("", request.paramKey());
        assertEquals(0.0, request.base());
        assertNull(request.payload());
        assertNull(request.scope());
    }

    @Test
    void scope_backFillsScopeIdAndPayload() {
        Object payload = new Object();
        CastScope scope = CastScope.builder().scopeId("mmo:ability:x").payload(payload).build();

        ParamFoldRequest request = ParamFoldRequest.builder().scope(scope).build();

        assertEquals("mmo:ability:x", request.scopeId());
        assertEquals(payload, request.payload());
        assertEquals(scope, request.scope());
    }

    @Test
    void explicitScopeId_winsOverScopeProjection_calledAfterScope() {
        CastScope scope = CastScope.builder().scopeId("from-scope").build();

        ParamFoldRequest request = ParamFoldRequest.builder()
                .scope(scope)
                .scopeId("explicit-override")
                .build();

        assertEquals("explicit-override", request.scopeId());
    }

    @Test
    void explicitScopeId_winsOverScopeProjection_calledBeforeScope() {
        CastScope scope = CastScope.builder().scopeId("from-scope").build();

        ParamFoldRequest request = ParamFoldRequest.builder()
                .scopeId("explicit-override")
                .scope(scope)
                .build();

        assertEquals("explicit-override", request.scopeId());
    }

    @Test
    void nullScope_clearsProjectionsWhenNotExplicitlyOverridden() {
        CastScope scope = CastScope.builder().scopeId("from-scope").payload("p").build();

        ParamFoldRequest request = ParamFoldRequest.builder()
                .scope(scope)
                .scope(null)
                .build();

        assertNull(request.scopeId());
        assertNull(request.payload());
        assertNull(request.scope());
    }

    @Test
    void paramKey_nullNormalizesToEmpty() {
        ParamFoldRequest request = ParamFoldRequest.builder().paramKey(null).build();
        assertEquals("", request.paramKey());
    }

    @Test
    void paramKey_blankNormalizesToEmpty() {
        ParamFoldRequest request = ParamFoldRequest.builder().paramKey("   ").build();
        assertEquals("", request.paramKey());
    }

    @Test
    void paramKey_realValuePassesThrough() {
        ParamFoldRequest request = ParamFoldRequest.builder().paramKey("damage").build();
        assertEquals("damage", request.paramKey());
    }

    @Test
    void base_passesThrough() {
        ParamFoldRequest request = ParamFoldRequest.builder().base(42.5).build();
        assertEquals(42.5, request.base());
    }

    @Test
    void toString_isNonNull() {
        assertNotNull(ParamFoldRequest.builder().build().toString());
    }
}
