package com.ziggfreed.common.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The slice of {@link Sound3D#playOn} testable without a live Hytale server: the entity-following
 * form guards a null or blank sound id and a null ref BEFORE it touches the asset map, the tracker
 * or any packet, answering zero viewers, so a caller's fall-back to a positional play is exercised
 * the same way it is when the engine has simply not shown the entity to anyone yet. The resolve,
 * network-id and delivery path needs a running server and is smoke-tested in the consuming mods.
 */
class Sound3DTest {

    @Test
    void playOn_nullOrBlankSoundId_answersNoViewers() {
        assertEquals(0, Sound3D.playOn(null, null, 1f, 1f, null, "TEST", false));
        assertEquals(0, Sound3D.playOn("", null, 1f, 1f, null, "TEST", true));
    }

    @Test
    void playOn_nullRef_answersNoViewers() {
        assertEquals(0, Sound3D.playOn("SFX_Some_Sound", null, 1f, 1f, null, "TEST", false));
    }
}
