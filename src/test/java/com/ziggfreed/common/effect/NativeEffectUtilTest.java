package com.ziggfreed.common.effect;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

/**
 * The slice of {@link NativeEffectUtil} testable without a live Hytale server: {@code apply} /
 * {@code applyFor} / {@code remove} all guard {@code ref == null} (and blank ids) BEFORE touching
 * the engine {@code EntityEffect} asset map or any {@code EffectControllerComponent}, and every
 * remaining engine touch sits behind {@code catch (Throwable)} - so a {@code null} ref degrades to
 * {@code false} the same way a bad ref / unregistered id / engine throw does live (fail-closed,
 * never propagate). The full resolve/apply/remove happy path needs a running server and is
 * smoke-tested in the consuming mods.
 */
class NativeEffectUtilTest {

    private static final Store<EntityStore> NULL_STORE = null;
    private static final Ref<EntityStore> NULL_REF = null;

    @Test
    void apply_nullRef_isANoOp() {
        assertFalse(NativeEffectUtil.apply(NULL_STORE, NULL_REF, "Some_Effect"));
    }

    @Test
    void apply_blankId_isANoOp() {
        assertFalse(NativeEffectUtil.apply(NULL_STORE, NULL_REF, ""));
    }

    @Test
    void apply_nullId_isANoOp() {
        assertFalse(NativeEffectUtil.apply(NULL_STORE, NULL_REF, null));
    }

    @Test
    void applyFor_nullRef_isANoOp() {
        assertFalse(NativeEffectUtil.applyFor(NULL_STORE, NULL_REF, "Some_Effect", 5.0f, OverlapBehavior.EXTEND));
    }

    @Test
    void applyFor_blankId_isANoOp() {
        assertFalse(NativeEffectUtil.applyFor(NULL_STORE, NULL_REF, "", 5.0f, OverlapBehavior.EXTEND));
    }

    @Test
    void remove_nullRef_isANoOp() {
        assertFalse(NativeEffectUtil.remove(NULL_STORE, NULL_REF, "Some_Effect"));
    }

    @Test
    void remove_blankId_isANoOp() {
        assertFalse(NativeEffectUtil.remove(NULL_STORE, NULL_REF, ""));
    }
}
