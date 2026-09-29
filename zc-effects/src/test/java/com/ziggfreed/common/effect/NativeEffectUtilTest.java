package com.ziggfreed.common.effect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

/**
 * The slice of {@link NativeEffectUtil} testable without a live Hytale server: {@code apply} /
 * {@code applyFor} / {@code remove} / {@code has} all guard {@code ref == null} (and blank ids) BEFORE touching
 * the engine {@code EntityEffect} asset map or any {@code EffectControllerComponent}, and every
 * remaining engine touch sits behind {@code catch (Throwable)} - so a {@code null} ref degrades to
 * {@code false} the same way a bad ref / unregistered id / engine throw does live (fail-closed,
 * never propagate). It also pins the unresolved-id latch ({@code warnUnresolvedOnce}): an id no
 * loaded effect resolves is named at WARN once per process whichever call meets it, keyed as the
 * engine's asset map resolves an id, so a second spelling of it stays quiet too. The full
 * resolve/apply/remove happy path needs a running server and is smoke-tested in the consuming mods.
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
    void applyInfinite_nullRef_isANoOp() {
        assertFalse(NativeEffectUtil.applyInfinite(NULL_STORE, NULL_REF, "Some_Effect"));
    }

    @Test
    void applyInfinite_blankId_isANoOp() {
        assertFalse(NativeEffectUtil.applyInfinite(NULL_STORE, NULL_REF, ""));
        assertFalse(NativeEffectUtil.applyInfinite(NULL_STORE, NULL_REF, null));
    }

    @Test
    void remove_nullRef_isANoOp() {
        assertFalse(NativeEffectUtil.remove(NULL_STORE, NULL_REF, "Some_Effect"));
    }

    @Test
    void remove_blankId_isANoOp() {
        assertFalse(NativeEffectUtil.remove(NULL_STORE, NULL_REF, ""));
    }

    @Test
    void has_nullRef_answersFalse() {
        assertFalse(NativeEffectUtil.has(NULL_STORE, NULL_REF, "Some_Effect"));
    }

    @Test
    void has_blankId_answersFalse() {
        assertFalse(NativeEffectUtil.has(NULL_STORE, NULL_REF, ""));
    }

    @Test
    void anUnresolvedIdIsNamedAtWarnOnceWhicheverCallMeetsIt() {
        assertTrue(NativeEffectUtil.warnUnresolvedOnce("apply", "Latch_Only_Effect"), "the first miss warns");
        assertFalse(NativeEffectUtil.warnUnresolvedOnce("apply", "Latch_Only_Effect"), "a repeat stays quiet");
        assertFalse(NativeEffectUtil.warnUnresolvedOnce("remove", "Latch_Only_Effect"),
                "and so does another call meeting the same id");
        assertTrue(NativeEffectUtil.warnUnresolvedOnce("remove", "Latch_Other_Effect"), "another id warns once too");
    }

    @Test
    void theLatchMatchesAnIdTheWayTheAssetMapDoes() {
        assertTrue(NativeEffectUtil.warnUnresolvedOnce("apply", "Latch_Case_Effect"));
        assertFalse(NativeEffectUtil.warnUnresolvedOnce("remove", "latch_case_effect"),
                "the asset map ignores case, so the latch does too");
    }
}
