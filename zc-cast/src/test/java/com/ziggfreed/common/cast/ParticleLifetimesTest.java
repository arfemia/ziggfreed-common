package com.ziggfreed.common.cast;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Whether a particle system provably ends on its own: only a positive own {@code LifeSpan} proves
 * it, zero or less is the engine's unlimited, and a system the server cannot read is unproven. The
 * live read runs here with no asset map loaded, which is exactly the unreadable case: it must answer
 * null (never throw), so an unknown system is never attached to an entity.
 */
class ParticleLifetimesTest {

    @Test
    void onlyAPositiveOwnLifeSpan_provesASystemEnds() {
        assertTrue(ParticleLifetimes.provablyEnds(2.0f));
        assertTrue(ParticleLifetimes.provablyEnds(0.05f), "any positive span ends");
        assertFalse(ParticleLifetimes.provablyEnds(0f), "zero is the engine's unlimited");
        assertFalse(ParticleLifetimes.provablyEnds(-1f), "below zero is unlimited too");
        assertFalse(ParticleLifetimes.provablyEnds(null), "an unreadable system is unproven");
    }

    @Test
    void aSystemTheServerCannotRead_neverProvesItEnds() {
        assertNull(ParticleLifetimes.lifeSpanOf("Fixture_Not_Loaded"), "no asset map loaded: unreadable, not a throw");
        assertFalse(ParticleLifetimes.systemProvablyEnds("Fixture_Not_Loaded"),
                "a system the server cannot read never rides");
    }

    @Test
    void aBlankOrMissingId_isUnreadable() {
        assertNull(ParticleLifetimes.lifeSpanOf(null));
        assertNull(ParticleLifetimes.lifeSpanOf(" "));
        assertFalse(ParticleLifetimes.systemProvablyEnds(null));
        assertFalse(ParticleLifetimes.systemProvablyEnds(""));
    }
}
