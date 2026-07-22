package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Try-guard contract tests for {@link ItemPropEntityService} - the "never throws, degrades to a
 * no-op" behaviour every entry point holds when handed a deliberately-broken (null) accessor or
 * ref. A blank item id short-circuits before any engine call; a non-blank id with a null
 * accessor falls through to the outer catch either way (whether {@code Item.getAssetMap()}
 * itself throws in a unit JVM, or resolves and then NPEs on the null accessor downstream), so
 * both are legitimate, deterministic no-live-server assertions. The SUCCESS paths (a real item
 * resolving to a spawned holder) touch live asset/Store/Holder types and have no unit coverage,
 * matching this ecosystem's established precedent (e.g. {@code station.StationCustodyDisplayTest}
 * for the sibling class this service was lifted from).
 */
class ItemPropEntityServiceTest {

    private static final Vector3d POS = new Vector3d(0, 0, 0);
    private static final Rotation3f ROT = new Rotation3f(0f, 0f, 0f);

    @Test
    void buildHolder_blankItemId_returnsNullWithoutTouchingAccessor() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertNull(assertDoesNotThrow(() -> ItemPropEntityService.buildHolder(nullAccessor, "", POS, ROT, 1f)));
        assertNull(assertDoesNotThrow(() -> ItemPropEntityService.buildHolder(nullAccessor, "   ", POS, ROT, 1f)));
    }

    @Test
    void buildHolder_withNullAccessor_returnsNullWithoutThrowing() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertNull(assertDoesNotThrow(() -> ItemPropEntityService.buildHolder(nullAccessor, "Some_Item", POS, ROT, 1f)));
    }

    @Test
    void spawn_withNullHolder_stillDoesNotThrow_becauseCallerNeverPassesOne() {
        // buildHolder never returns a non-null holder without a live server (see above), so the
        // one-call convenience overload composes safely: null-holder short-circuits to null.
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertNull(assertDoesNotThrow(() -> ItemPropEntityService.spawn(nullAccessor, "Some_Item", POS, ROT, 1f)));
    }

    @Test
    void spawn_holderOverload_withNullAccessor_returnsNullWithoutThrowing() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        Holder<EntityStore> nullHolder = null;
        assertNull(assertDoesNotThrow(() -> ItemPropEntityService.spawn(nullAccessor, nullHolder)));
    }

    @Test
    void despawn_withNullRef_isNoOpOnBothOverloads() {
        Store<EntityStore> store = null;
        CommandBuffer<EntityStore> commandBuffer = null;
        Ref<EntityStore> nullRef = null;
        assertDoesNotThrow(() -> ItemPropEntityService.despawn(nullRef, store));
        assertDoesNotThrow(() -> ItemPropEntityService.despawn(nullRef, commandBuffer));
    }

    @Test
    void despawn_withNullCommandBuffer_isNoOp() {
        assertDoesNotThrow(() -> ItemPropEntityService.despawn(null, (CommandBuffer<EntityStore>) null));
    }
}
