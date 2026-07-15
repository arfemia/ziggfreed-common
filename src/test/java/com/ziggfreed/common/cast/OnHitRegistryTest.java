package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Characterization tests for the instantiable {@link OnHitRegistry}. All builders write to a
 * shared {@link StringBuilder} and the resulting {@link BiConsumer} is invoked with null
 * store/ref (the generic core never touches the engine), so no Hytale objects are needed.
 */
class OnHitRegistryTest {

    /** A {@code Map<String,Object>} literal (so the back-compat Map overload is reachable). */
    private static Map<String, Object> typeMap(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    /** A builder that appends {@code token} to {@code sink} each time its consumer runs. */
    private static OnHitRegistry.OnHitBuilder appender(StringBuilder sink, String token) {
        return (params, sourceRef, sourcePlayerId) -> (store, target) -> sink.append(token);
    }

    private static void fire(BiConsumer<Store<EntityStore>, Ref<EntityStore>> consumer) {
        consumer.accept(null, null);
    }

    @Test
    void singleMapDispatchesToRegisteredBuilder_backCompatOverload() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("STATUS", appender(sink, "S"));

        // A Map<String,Object> binds to the back-compat fromParams(Map,...) overload.
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> c = reg.fromParams(typeMap("STATUS"), null, null);
        assertNotSame(OnHitRegistry.NO_OP, c, "a registered type resolves to a real consumer");
        fire(c);
        assertEquals("S", sink.toString());
    }

    @Test
    void singleMapDispatchesToRegisteredBuilder_objectOverload() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("STATUS", appender(sink, "S"));

        // An Object-typed single map goes through the Object overload's instanceof-Map path.
        Object onHit = Map.of("type", "STATUS");
        fire(reg.fromParams(onHit, null, null));
        assertEquals("S", sink.toString());
    }

    @Test
    void listFormChainsInDeclarationOrder() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("A", appender(sink, "A"));
        reg.register("B", appender(sink, "B"));
        reg.register("C", appender(sink, "C"));

        Object onHit = List.of(
                Map.of("type", "A"),
                Map.of("type", "B"),
                Map.of("type", "C"));
        fire(reg.fromParams(onHit, null, null));
        assertEquals("ABC", sink.toString(), "list entries chain in declaration order");
    }

    @Test
    void nullMalformedAndUnknownResolveToNoOp() {
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("KNOWN", appender(new StringBuilder(), "K"));

        assertSame(OnHitRegistry.NO_OP, reg.fromParams((Object) null, null, null), "null onHit");
        assertSame(OnHitRegistry.NO_OP, reg.fromParams("not-a-map-or-list", null, null), "wrong type");
        assertSame(OnHitRegistry.NO_OP, reg.fromParams(Map.of("type", "MISSING"), null, null), "unknown type");
        assertSame(OnHitRegistry.NO_OP, reg.fromParams(Map.of("nope", "x"), null, null), "no type key");
    }

    @Test
    void emptyMapAndEmptyListResolveToNoOp() {
        OnHitRegistry reg = new OnHitRegistry();
        Map<String, Object> empty = new LinkedHashMap<>();
        assertSame(OnHitRegistry.NO_OP, reg.fromParams(empty, null, null), "empty map (back-compat overload isEmpty check)");
        assertSame(OnHitRegistry.NO_OP, reg.fromParams(List.of(), null, null), "empty list");
    }

    @Test
    void unknownEntriesInAListAreSkippedButKnownOnesStillChain() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("A", appender(sink, "A"));
        reg.register("B", appender(sink, "B"));

        Object onHit = List.of(
                Map.of("type", "A"),
                Map.of("type", "UNKNOWN"),   // skipped (NO_OP)
                "not-a-map",                 // skipped (not a Map)
                Map.of("type", "B"));
        fire(reg.fromParams(onHit, null, null));
        assertEquals("AB", sink.toString(), "NO_OP + non-map entries drop out of the chain");
    }

    @Test
    void aBuilderThatThrowsDegradesToNoOp() {
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("BOOM", (params, sourceRef, sourcePlayerId) -> {
            throw new IllegalStateException("builder blew up");
        });
        assertSame(OnHitRegistry.NO_OP, reg.fromParams(typeMap("BOOM"), null, null));
    }

    @Test
    void typeKeyingIsCaseInsensitive() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.register("status", appender(sink, "S"));                // lower-case registration
        fire(reg.fromParams(typeMap("StAtUs"), null, null));        // mixed-case lookup
        assertEquals("S", sink.toString(), "register + lookup both upper-case the key");
    }

    @Test
    void twoInstancesAreIsolated() {
        StringBuilder sinkA = new StringBuilder();
        StringBuilder sinkB = new StringBuilder();
        OnHitRegistry regA = new OnHitRegistry();
        OnHitRegistry regB = new OnHitRegistry();
        regA.register("ONLY_A", appender(sinkA, "A"));
        regB.register("ONLY_B", appender(sinkB, "B"));

        // regB does not know ONLY_A, and regA does not know ONLY_B.
        assertSame(OnHitRegistry.NO_OP, regB.fromParams(typeMap("ONLY_A"), null, null));
        assertSame(OnHitRegistry.NO_OP, regA.fromParams(typeMap("ONLY_B"), null, null));

        fire(regA.fromParams(typeMap("ONLY_A"), null, null));
        fire(regB.fromParams(typeMap("ONLY_B"), null, null));
        assertEquals("A", sinkA.toString());
        assertEquals("B", sinkB.toString());
    }

    // ==================== HitAction path (registerAction / actionFromParams) ====================

    /** A HitAction builder that appends {@code token} to {@code sink} each time its action runs. */
    private static OnHitRegistry.HitActionBuilder actionAppender(StringBuilder sink, String token) {
        return (params, sourceRef, sourcePlayerId) -> ctx -> sink.append(token);
    }

    @Test
    void actionSingleMapDispatchesToRegisteredActionBuilder() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.registerAction("STATUS", actionAppender(sink, "S"));

        HitAction a = reg.actionFromParams(typeMap("STATUS"), null, null);
        assertNotSame(OnHitRegistry.NO_OP_ACTION, a, "a registered type resolves to a real action");
        a.onHit(null);
        assertEquals("S", sink.toString());
    }

    @Test
    void actionListFormChainsInDeclarationOrder() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        reg.registerAction("A", actionAppender(sink, "A"));
        reg.registerAction("B", actionAppender(sink, "B"));
        reg.registerAction("C", actionAppender(sink, "C"));

        Object onHit = List.of(
                Map.of("type", "A"),
                Map.of("type", "B"),
                Map.of("type", "C"));
        reg.actionFromParams(onHit, null, null).onHit(null);
        assertEquals("ABC", sink.toString(), "list entries chain in declaration order");
    }

    @Test
    void actionNullMalformedAndUnknownResolveToNoOpAction() {
        OnHitRegistry reg = new OnHitRegistry();
        reg.registerAction("KNOWN", actionAppender(new StringBuilder(), "K"));

        assertSame(OnHitRegistry.NO_OP_ACTION, reg.actionFromParams((Object) null, null, null), "null onHit");
        assertSame(OnHitRegistry.NO_OP_ACTION, reg.actionFromParams("not-a-map", null, null), "wrong type");
        assertSame(OnHitRegistry.NO_OP_ACTION, reg.actionFromParams(Map.of("type", "MISSING"), null, null), "unknown type");
        assertSame(OnHitRegistry.NO_OP_ACTION, reg.actionFromParams(Map.of("nope", "x"), null, null), "no type key");
    }

    @Test
    void actionAndBiConsumerTablesDoNotCollide() {
        StringBuilder sink = new StringBuilder();
        OnHitRegistry reg = new OnHitRegistry();
        // Register the SAME type id on both tables with different tokens.
        reg.register("SHARED", appender(sink, "biconsumer"));
        reg.registerAction("SHARED", actionAppender(sink, "action"));

        // The BiConsumer path sees only the BiConsumer builder.
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> c = reg.fromParams(typeMap("SHARED"), null, null);
        fire(c);
        assertEquals("biconsumer", sink.toString());

        // The HitAction path sees only the HitAction builder.
        sink.setLength(0);
        reg.actionFromParams(typeMap("SHARED"), null, null).onHit(null);
        assertEquals("action", sink.toString());

        // A type registered on ONE table is invisible to the other.
        OnHitRegistry reg2 = new OnHitRegistry();
        reg2.register("ONLY_BICONSUMER", appender(new StringBuilder(), "x"));
        assertSame(OnHitRegistry.NO_OP_ACTION, reg2.actionFromParams(typeMap("ONLY_BICONSUMER"), null, null),
                "a BiConsumer-only type is a no-op on the action path");
    }

    @Test
    void aThrowingActionBuilderDegradesToNoOpAction() {
        OnHitRegistry reg = new OnHitRegistry();
        reg.registerAction("BOOM", (params, sourceRef, sourcePlayerId) -> {
            throw new IllegalStateException("action builder blew up");
        });
        assertSame(OnHitRegistry.NO_OP_ACTION, reg.actionFromParams(typeMap("BOOM"), null, null));
    }
}
