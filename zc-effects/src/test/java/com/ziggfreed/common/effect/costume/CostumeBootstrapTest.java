package com.ziggfreed.common.effect.costume;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.interaction.type.InteractionTypeSpec;

/**
 * The costume Type registers under the name content authors. Building the spec never builds the
 * codec, and a class literal only loads a class, so nothing here reaches Interaction's class init.
 */
class CostumeBootstrapTest {

    @Test
    void theCostumeTypeRegistersUnderItsAuthoredName() {
        InteractionTypeSpec spec = CostumeBootstrap.spec();

        assertEquals("ZigCostume", spec.typeName());
        assertEquals(ZigCostumeInteraction.class, spec.type());
    }
}
