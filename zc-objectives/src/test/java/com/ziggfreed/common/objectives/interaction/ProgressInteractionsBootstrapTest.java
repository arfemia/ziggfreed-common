package com.ziggfreed.common.objectives.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.interaction.type.InteractionTypeSpec;

/**
 * The Types register under the names content authors. Building a spec never builds its codec
 * (InteractionTypeSpec holds a supplier), and a class literal only loads a class, so nothing here
 * reaches the engine's Interaction class init.
 */
class ProgressInteractionsBootstrapTest {

    @Test
    void bothTypesRegisterUnderTheirAuthoredNames() {
        List<InteractionTypeSpec> specs = ProgressInteractionsBootstrap.specs();

        assertEquals(List.of("ZigCreditProgress", "ZigGrantReward"),
                specs.stream().map(InteractionTypeSpec::typeName).toList());
        assertEquals(ZigCreditProgressInteraction.class, specs.get(0).type());
        assertEquals(ZigGrantRewardInteraction.class, specs.get(1).type());
    }
}
