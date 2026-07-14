package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Characterization tests for {@link ArmedStateStore}. Every path here passes null store/ref
 * (the store keeps pure per-caster state; the only engine types are the {@code BiConsumer}
 * generic params, never dereferenced), so no Hytale runtime objects are needed. The attribution
 * payload is an OPAQUE {@code Object} - these tests use plain Strings as stand-in payloads.
 * Expiry tests use generous margins (a 40ms window read after a 120ms sleep).
 */
class ArmedStateStoreTest {

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void compositeMultiplierIsTheProductAcrossSlots() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 1.5, 0.0, 60_000, null, null);
        store.armNextHit(id, "b", ArmedStateStore.SLOT_AURA, 2.0, 0.0, 60_000, null, null);

        assertEquals(3.0, store.peekArmedMultiplier(id), 1e-9, "peek is the product without consuming");
        ArmedStateStore.ArmedState composite = store.consumeArmed(id);
        assertNotNull(composite);
        assertEquals(3.0, composite.multiplier, 1e-9);
    }

    @Test
    void flatDamageSumsAcrossSlots() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 1.0, 10.0, 60_000, null, null);
        store.armNextHit(id, "b", ArmedStateStore.SLOT_AURA, 1.0, 5.0, 60_000, null, null);

        ArmedStateStore.ArmedState composite = store.consumeArmed(id);
        assertNotNull(composite);
        assertEquals(15.0, composite.flatDamage, 1e-9, "flats add across slots");
        assertEquals(1.0, composite.multiplier, 1e-9, "no multiplier armed");
    }

    @Test
    void onHitConsumersChainAcrossSlots() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        StringBuilder sink = new StringBuilder();
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> a = (s, r) -> sink.append("A");
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> b = (s, r) -> sink.append("B");
        // Stun-only style arm: multiplier 1.0, flat 0, kept live only by the on-hit consumer.
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 1.0, 0.0, 60_000, a, null);
        store.armNextHit(id, "b", ArmedStateStore.SLOT_DODGE, 1.0, 0.0, 60_000, b, null);

        ArmedStateStore.ArmedState composite = store.consumeArmed(id);
        assertNotNull(composite, "an on-hit-only composite still resolves");
        assertNotNull(composite.onHitConsumer);
        composite.onHitConsumer.accept(null, null);
        // Slot iteration order is unspecified, so assert both fired (order-independent).
        assertEquals(2, sink.length());
        assertTrue(sink.indexOf("A") >= 0 && sink.indexOf("B") >= 0, "both slot consumers chained");
    }

    @Test
    void attributionsAreExposedPerLiveSlotForTheConsumerToFold() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        Object payloadA = "attr-A";
        Object payloadB = "attr-B";
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 1.5, 0.0, 60_000, null, payloadA);
        store.armNextHit(id, "b", ArmedStateStore.SLOT_AURA, 1.5, 0.0, 60_000, null, payloadB);

        ArmedStateStore.ArmedState composite = store.consumeArmed(id);
        assertNotNull(composite);
        assertEquals(2, composite.attributions.size(), "every live slot's attribution is exposed");
        assertTrue(composite.attributions.contains(payloadA));
        assertTrue(composite.attributions.contains(payloadB));
    }

    @Test
    void nullAttributionYieldsAnEmptyCompositeList() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 2.0, 0.0, 60_000, null, null);

        ArmedStateStore.ArmedState composite = store.consumeArmed(id);
        assertNotNull(composite);
        assertTrue(composite.attributions.isEmpty(), "no attribution -> empty (never null) list");
    }

    @Test
    void payloadFreeArmMultiplierOverloadStillWorks() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        // The retained payload-free convenience overload.
        store.armMultiplier(id, 2.5, 60_000);

        assertEquals(2.5, store.peekArmedMultiplier(id), 1e-9);
        ArmedStateStore.ArmedState composite = store.consumeArmed(id);
        assertNotNull(composite);
        assertEquals(2.5, composite.multiplier, 1e-9);
        assertNull(composite.abilityId, "no ability id via the convenience overload");
        assertTrue(composite.attributions.isEmpty());
    }

    @Test
    void armedStateFieldsRoundTripThroughTheConstructor() {
        // ArmedState is now final-field + ctor; construct one directly and read it back.
        BiConsumer<Store<EntityStore>, Ref<EntityStore>> onHit = (s, r) -> { };
        ArmedStateStore.ArmedState state = new ArmedStateStore.ArmedState(
                "flame", ArmedStateStore.SLOT_MOMENTUM, 1.75, 8.0, 123L, onHit, List.of("x"));
        assertEquals("flame", state.abilityId);
        assertEquals(ArmedStateStore.SLOT_MOMENTUM, state.slot);
        assertEquals(1.75, state.multiplier, 1e-9);
        assertEquals(8.0, state.flatDamage, 1e-9);
        assertEquals(123L, state.expiresAtMs);
        assertSame(onHit, state.onHitConsumer);
        assertEquals(List.of("x"), state.attributions);
    }

    @Test
    void consumeRemovesTheArmedState() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 2.0, 0.0, 60_000, null, null);

        assertNotNull(store.consumeArmed(id), "first consume returns the composite");
        assertNull(store.consumeArmed(id), "second consume is empty");
        assertEquals(1.0, store.peekArmedMultiplier(id), 1e-9);
    }

    @Test
    void unmeaningfulArmIsANoOp() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        // multiplier <= 1.0, flat 0, no consumer -> nothing is armed.
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 1.0, 0.0, 60_000, null, null);
        assertEquals(1.0, store.peekArmedMultiplier(id), 1e-9);
        assertNull(store.consumeArmed(id));
        assertFalse(store.hasArmedSlot(id, ArmedStateStore.SLOT_PRIMARY));
    }

    @Test
    void armedStateExpires() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 2.0, 0.0, 40, null, null);
        assertTrue(store.hasArmedSlot(id, ArmedStateStore.SLOT_PRIMARY), "live right after arming");
        sleep(120);
        assertFalse(store.hasArmedSlot(id, ArmedStateStore.SLOT_PRIMARY), "expired after the window");
        assertEquals(1.0, store.peekArmedMultiplier(id), 1e-9, "expired slot pruned on peek");
        assertNull(store.consumeArmed(id), "nothing live to consume");
    }

    @Test
    void invulnerabilityWindowSetsAndExpires() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        Object attribution = "dash-attr";
        store.armInvulnerability(id, 40, "dash", attribution);
        assertTrue(store.isInvulnerable(id), "live right after arming");
        assertNotNull(store.peekInvulnerability(id));
        assertEquals("dash", store.peekInvulnerability(id).abilityId);
        assertSame(attribution, store.peekInvulnerability(id).attribution, "opaque attribution round-trips");
        sleep(120);
        assertFalse(store.isInvulnerable(id), "expired after the window");
        assertNull(store.peekInvulnerability(id));
    }

    @Test
    void clearInvulnerabilityDropsItEarly() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armInvulnerability(id, 60_000, "dash", null);
        assertTrue(store.isInvulnerable(id));
        store.clearInvulnerability(id);
        assertFalse(store.isInvulnerable(id));
    }

    @Test
    void clearPlayerWipesArmedAndInvulnerable() {
        ArmedStateStore store = new ArmedStateStore();
        UUID id = UUID.randomUUID();
        store.armNextHit(id, "a", ArmedStateStore.SLOT_PRIMARY, 2.0, 0.0, 60_000, null, null);
        store.armInvulnerability(id, 60_000, "dash", null);

        store.clearPlayer(id);
        assertEquals(1.0, store.peekArmedMultiplier(id), 1e-9, "armed wiped");
        assertNull(store.consumeArmed(id));
        assertFalse(store.isInvulnerable(id), "invulnerability wiped");
    }
}
