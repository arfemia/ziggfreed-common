package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Characterization tests for {@link HitResolver}, {@link HitContext}, and {@link HitAction}.
 *
 * <p>The applyHit tests use a null {@code store} + null {@code target} + null damage source/cause (the
 * test {@link HitResolver.DamageSink} ignores them and NEVER constructs an engine {@code Damage}, so
 * no server / asset-store is needed) and observe behavior through recording lambdas. The
 * accessor-forms test proves BOTH {@code Store} and {@code CommandBuffer} are assignable to the
 * {@link HitContext} accessor slot (the compile-time guarantee) and round-trips the constructible
 * scalar fields with real values.
 */
class HitResolverTest {

    // ==================== HitContext ====================

    @Test
    void hitContextRoundTripsScalarFieldsAndAcceptsBothAccessorForms() {
        UUID player = UUID.randomUUID();
        Vector3d pos = new Vector3d(1, 2, 3);

        // A Store-typed accessor is assignable to the ComponentAccessor slot (compile-time proof).
        Store<EntityStore> storeAccessor = null;
        HitContext viaStore = HitContext.builder()
                .accessor(storeAccessor)
                .target(null)
                .sourcePlayerId(player)
                .damageAmount(42.0)
                .position(pos)
                .cause("test-cause")
                .build();
        assertSame(player, viaStore.sourcePlayerId());
        assertEquals(42.0, viaStore.damageAmount(), 1e-9);
        assertSame(pos, viaStore.position());
        assertEquals("test-cause", viaStore.cause());
        assertNull(viaStore.accessor(), "the (null) store flowed into the accessor slot");

        // A CommandBuffer-typed accessor is ALSO assignable - the whole point of the accessor being
        // a ComponentAccessor, not a Store (closes the deferred-mutation gap).
        CommandBuffer<EntityStore> bufferAccessor = null;
        HitContext viaBuffer = HitContext.builder()
                .accessor(bufferAccessor)
                .target(null)
                .build();
        assertNull(viaBuffer.accessor());
        assertEquals(0.0, viaBuffer.damageAmount(), 1e-9, "unset amount defaults to 0");
    }

    // ==================== HitAction ====================

    @Test
    void hitActionNoOpFiresNothingAndAndThenChains() {
        List<String> order = new ArrayList<>();
        HitAction a = ctx -> order.add("A");
        HitAction b = ctx -> order.add("B");

        // NO_OP is a real callable that does nothing.
        HitAction.NO_OP.onHit(null);
        assertTrue(order.isEmpty());

        // andThen composes in order; a NO_OP operand is dropped (no dead link).
        a.andThen(b).andThen(HitAction.NO_OP).onHit(null);
        assertEquals(List.of("A", "B"), order);

        // NO_OP.andThen(x) == x (identity front); x.andThen(NO_OP) == x (identity back).
        assertSame(a, HitAction.NO_OP.andThen(a));
        assertSame(a, a.andThen(HitAction.NO_OP));
        assertSame(a, a.andThen(null));
    }

    // ==================== HitResolver.applyHit ====================

    @Test
    void originDecoratorWrapsExactlyTheDamageDispatchThenOnHitThenSound() {
        List<String> order = new ArrayList<>();
        HitContext[] captured = new HitContext[1];

        UnaryOperator<Runnable> decorator = body -> () -> {
            order.add("origin-in");
            body.run();
            order.add("origin-out");
        };
        HitAction onHit = ctx -> {
            order.add("onhit");
            captured[0] = ctx;
        };
        BiConsumer<Store<EntityStore>, Vector3d> sound = (s, pos) -> order.add("sound");
        Vector3d hitPos = new Vector3d(4, 5, 6);

        HitResolver.HitRequest req = HitResolver.HitRequest.builder()
                .sink((target, source, cause, amount) -> order.add("dispatch"))
                .amount(5f)
                .originDecorator(decorator)
                .onHit(onHit)
                .perHitSound(sound)
                .failLabel("Test")
                .build();

        boolean ok = HitResolver.applyHit(null, req, null, hitPos);
        assertTrue(ok, "a non-throwing dispatch returns true");
        // The decorator brackets ONLY the dispatch; onHit + sound run AFTER origin-out.
        assertEquals(List.of("origin-in", "dispatch", "origin-out", "onhit", "sound"), order);

        // The HitAction received a context built from the applyHit store + amount + position.
        assertNotNull(captured[0]);
        assertNull(captured[0].accessor(), "the (null) store is the accessor");
        assertEquals(5.0, captured[0].damageAmount(), 1e-9);
        assertSame(hitPos, captured[0].position());
    }

    @Test
    void biConsumerBridgeFiresWithStoreAndTarget() {
        List<String> order = new ArrayList<>();
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> bc = (store, target) -> order.add("onhit-bc");

        HitResolver.HitRequest req = HitResolver.HitRequest.builder()
                .sink((target, source, cause, amount) -> order.add("dispatch"))
                .onHit(bc)
                .build();

        boolean ok = HitResolver.applyHit(null, req, null, null);
        assertTrue(ok);
        assertEquals(List.of("dispatch", "onhit-bc"), order, "the BiConsumer bridge fires after dispatch");
    }

    @Test
    void lastOnHitSetterWins() {
        List<String> order = new ArrayList<>();
        HitAction action = ctx -> order.add("action");
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> bc = (s, t) -> order.add("bc");

        // Set the HitAction, then override with the BiConsumer - only the BiConsumer should fire.
        HitResolver.HitRequest req = HitResolver.HitRequest.builder()
                .sink((target, source, cause, amount) -> { })
                .onHit(action)
                .onHit(bc)
                .build();
        HitResolver.applyHit(null, req, null, null);
        assertEquals(List.of("bc"), order, "the last onHit setter wins (BiConsumer over the prior HitAction)");
    }

    @Test
    void aThrowingDispatchReturnsFalseAndSkipsOnHit() {
        List<String> order = new ArrayList<>();
        HitAction onHit = ctx -> order.add("onhit");

        HitResolver.HitRequest req = HitResolver.HitRequest.builder()
                .sink((target, source, cause, amount) -> { throw new IllegalStateException("dispatch boom"); })
                .onHit(onHit)
                .warnOnDamageFailure(true)
                .failLabel("Test")
                .build();

        boolean ok = HitResolver.applyHit(null, req, null, null);
        assertFalse(ok, "a throwing dispatch returns false");
        assertTrue(order.isEmpty(), "onHit never runs when the dispatch throws");
    }

    @Test
    void aThrowingOnHitIsCaughtAndDispatchStillCounts() {
        List<String> order = new ArrayList<>();
        HitAction onHit = ctx -> { throw new IllegalStateException("onhit boom"); };

        HitResolver.HitRequest req = HitResolver.HitRequest.builder()
                .sink((target, source, cause, amount) -> order.add("dispatch"))
                .onHit(onHit)
                .build();

        boolean ok = HitResolver.applyHit(null, req, null, null);
        assertTrue(ok, "a throwing onHit is caught; the successful dispatch still returns true");
        assertEquals(List.of("dispatch"), order);
    }

    @Test
    void noOnHitIsFineAndSoundOnlyFiresWithAPosition() {
        List<String> order = new ArrayList<>();
        BiConsumer<Store<EntityStore>, Vector3d> sound = (s, pos) -> order.add("sound");

        HitResolver.HitRequest req = HitResolver.HitRequest.builder()
                .sink((target, source, cause, amount) -> order.add("dispatch"))
                .perHitSound(sound)
                .build();

        // Null hitPos -> the per-hit sound is skipped (it needs a position).
        HitResolver.applyHit(null, req, null, null);
        assertEquals(List.of("dispatch"), order, "sound is skipped with a null position");

        order.clear();
        HitResolver.applyHit(null, req, null, new Vector3d(0, 0, 0));
        assertEquals(List.of("dispatch", "sound"), order, "sound fires when a position is present");
    }
}
