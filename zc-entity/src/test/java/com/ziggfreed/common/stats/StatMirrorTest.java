package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;

/**
 * {@link StatMirror#decideOrSkip} idempotence - the pure core behind {@link StatMirror#set}.
 * {@link StaticModifier} is a plain constructible POJO (no live server needed), so no fake seam
 * is required here, unlike {@link EquipStatBridgeTest}.
 */
class StatMirrorTest {

    @Test
    void noExistingModifierWritesTheNewAmount() {
        Float toWrite = StatMirror.decideOrSkip(null, 10.0f);
        assertEquals(10.0f, toWrite);
    }

    @Test
    void equalExistingModifierIsIdempotentNoOp() {
        StaticModifier existing = new StaticModifier(Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.ADDITIVE, 10.0f);
        assertNull(StatMirror.decideOrSkip(existing, 10.0f));
    }

    @Test
    void differentAmountWritesTheNewOne() {
        StaticModifier existing = new StaticModifier(Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.ADDITIVE, 10.0f);
        assertEquals(15.0f, StatMirror.decideOrSkip(existing, 15.0f));
    }

    @Test
    void differentCalculationTypeIsNotConsideredEqualEvenAtTheSameAmount() {
        StaticModifier existing = new StaticModifier(Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.MULTIPLICATIVE, 10.0f);
        assertEquals(10.0f, StatMirror.decideOrSkip(existing, 10.0f));
    }

    @Test
    void differentTargetIsNotConsideredEqualEvenAtTheSameAmount() {
        StaticModifier existing = new StaticModifier(Modifier.ModifierTarget.MIN,
                StaticModifier.CalculationType.ADDITIVE, 10.0f);
        assertEquals(10.0f, StatMirror.decideOrSkip(existing, 10.0f));
    }

    @Test
    void nonStaticModifierIsNotConsideredEqual() {
        Modifier nonStatic = new Modifier(Modifier.ModifierTarget.MAX) {
            @Override
            public float apply(float statValue) {
                return statValue;
            }
        };
        assertEquals(10.0f, StatMirror.decideOrSkip(nonStatic, 10.0f));
    }
}
