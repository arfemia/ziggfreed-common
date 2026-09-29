package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.world.PlaySoundEventEntity;

/**
 * The slice of {@link EntityViewers} testable without a live Hytale server: a null ref answers zero
 * viewers BEFORE the tracker component is ever read, which is the same answer a fresh entity gives
 * live (the tracker has shown it to nobody yet), so a caller's "fall back on zero" path is one path.
 * A real viewer map needs a running server and is smoke-tested in the consuming mods.
 */
class EntityViewersTest {

    @Test
    void aNullRefAnswersNoViewers() {
        assertEquals(0, EntityViewers.deliver(null, null, new PlaySoundEventEntity(1, 2, 1f, 1f)));
    }
}
