package com.ziggfreed.common.sound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.SoundCategory;

/**
 * The slice of {@link BlockStateSound} testable without a live Hytale server: a world whose blocks
 * cannot be read plays nothing and throws nothing into the caller (a Kweebec shrine lighting, say), in
 * both the interaction and the ambient form. No world at all stands in for one here: the block read
 * degrades to an empty cell before any block type or sound is touched, and a failure is logged through
 * the guarded {@code SafeLog}, never the raw logger, which throws an {@code Error} in a unit JVM. The
 * live read and the sound itself are smoke-tested in game.
 */
class BlockStateSoundTest {

    @Test
    void aWorldWhoseBlocksCannotBeReadPlaysNothingAndThrowsNothing() {
        assertDoesNotThrow(() -> BlockStateSound.playInteractionSound(null, 0, 64, 0, "On",
                SoundCategory.SFX, null, "TEST"));
        assertDoesNotThrow(() -> BlockStateSound.playAmbientSound(null, 0, 64, 0, "On",
                SoundCategory.SFX, null, "TEST"));
    }
}
