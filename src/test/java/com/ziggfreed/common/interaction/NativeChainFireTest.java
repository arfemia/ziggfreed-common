package com.ziggfreed.common.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hypixel.hytale.protocol.InteractionType;
import org.junit.jupiter.api.Test;

/**
 * The slice of {@link NativeChainFire} testable without a live Hytale server: every engine touch
 * (the {@code InteractionManager} component fetch, the {@code RootInteraction} asset-map lookup,
 * {@code initChain}/{@code queueExecuteChain}) sits behind one {@code catch (Throwable)}, so a
 * {@code null} store/ref/id degrades to {@code false} the same way an unresolved id or an engine
 * throw does in a live server (fail-closed, skip + one warn, never propagate) - the full
 * resolve/fire happy path needs a running server and is smoke-tested in the consuming mods.
 */
class NativeChainFireTest {

    @Test
    void fire_nullStoreAndRef_isANoOp() {
        assertFalse(NativeChainFire.fire(null, null, "Some_Interaction", InteractionType.Ability1));
    }

    @Test
    void fire_blankInteractionId_isANoOp() {
        assertFalse(NativeChainFire.fire(null, null, "", InteractionType.Ability1));
    }

    @Test
    void fire_nullInteractionId_isANoOp() {
        assertFalse(NativeChainFire.fire(null, null, null, InteractionType.Ability1));
    }
}
