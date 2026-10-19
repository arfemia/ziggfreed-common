package com.ziggfreed.common.effect.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionTarget;

/**
 * Each control Type reads its leaves as written, and an unauthored one reads as its documented default. Tagged
 * {@code engine-items}, every method: a Type's codec chains into {@code Interaction}, whose class init needs the
 * engine's log manager.
 */
class ControlTypeCodecTest {

    private static <T> T decode(BuilderCodec<T> codec, String json) throws IOException {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Tag("engine-items")
    @Test
    void aStunReadsItsLeaves() throws IOException {
        ZigStunInteraction stun = decode(ZigStunInteraction.getCODEC(),
                "{ \"Entity\": \"Owner\", \"Duration\": 1.5, \"Stagger\": true, \"ImmunityRamp\": 0 }");
        assertEquals(InteractionTarget.OWNER, stun.entity());
        assertEquals(1.5, stun.duration());
        assertTrue(stun.isStagger());
        assertEquals(0.0, stun.immunityRamp());
    }

    @Tag("engine-items")
    @Test
    void anUnauthoredStunIsATwoSecondStunOnTheTargetWithTheBombsRamp() throws IOException {
        ZigStunInteraction stun = decode(ZigStunInteraction.getCODEC(), "{}");
        assertEquals(InteractionTarget.TARGET, stun.entity());
        assertEquals(ZigStunInteraction.DEFAULT_DURATION, stun.duration());
        assertFalse(stun.isStagger());
        assertEquals(ZigStunInteraction.DEFAULT_IMMUNITY_RAMP, stun.immunityRamp());
    }

    @Tag("engine-items")
    @Test
    void aTauntReadsItsLeavesAndDefaultsToItsOwner() throws IOException {
        ZigTauntInteraction taunt = decode(ZigTauntInteraction.getCODEC(),
                "{ \"Entity\": \"Target\", \"Radius\": 30, \"Duration\": 2.5 }");
        assertEquals(InteractionTarget.TARGET, taunt.entity());
        assertEquals(30.0, taunt.radius());
        assertEquals(2.5, taunt.duration());

        ZigTauntInteraction plain = decode(ZigTauntInteraction.getCODEC(), "{}");
        assertEquals(InteractionTarget.OWNER, plain.entity());
        assertEquals(ZigTauntInteraction.DEFAULT_RADIUS, plain.radius());
        assertEquals(ZigTauntInteraction.DEFAULT_DURATION, plain.duration());
    }

    @Tag("engine-items")
    @Test
    void aDropReadsItsLeavesAndDefaultsToItsOwner() throws IOException {
        ZigAggroDropInteraction drop = decode(ZigAggroDropInteraction.getCODEC(),
                "{ \"Entity\": \"User\", \"Radius\": 0 }");
        assertEquals(InteractionTarget.USER, drop.entity());
        assertEquals(0.0, drop.radius());

        ZigAggroDropInteraction plain = decode(ZigAggroDropInteraction.getCODEC(), "{}");
        assertEquals(InteractionTarget.OWNER, plain.entity());
        assertEquals(ZigAggroDropInteraction.DEFAULT_RADIUS, plain.radius());
    }

    @Tag("engine-items")
    @Test
    void aSuppressReadsItsLeavesAndDefaultsToItsOwner() throws IOException {
        ZigAggroSuppressInteraction suppress = decode(ZigAggroSuppressInteraction.getCODEC(),
                "{ \"Radius\": 30, \"Duration\": 6.2 }");
        assertEquals(InteractionTarget.OWNER, suppress.entity());
        assertEquals(30.0, suppress.radius());
        assertEquals(6.2, suppress.duration());

        ZigAggroSuppressInteraction plain = decode(ZigAggroSuppressInteraction.getCODEC(), "{}");
        assertEquals(ZigAggroSuppressInteraction.DEFAULT_RADIUS, plain.radius());
        assertEquals(ZigAggroSuppressInteraction.DEFAULT_DURATION, plain.duration());
    }
}
