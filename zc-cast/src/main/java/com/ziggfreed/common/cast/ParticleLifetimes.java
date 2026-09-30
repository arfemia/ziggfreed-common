package com.ziggfreed.common.cast;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;

/**
 * Whether a native particle system provably ENDS ON ITS OWN, the question a caller asks before it
 * attaches a system to an entity through {@link ModelParticleService#spawnOn}. That route carries no
 * playback cap (the protocol's attached-particle leaf has no duration), so an attached system lives
 * until its own lifetime ends or its entity is removed, and a system that never ends rides its
 * entity for as long as the entity stands. A caller with such a system keeps it world-positioned
 * under a cap instead ({@link ModelParticleService#spawnAt(com.hypixel.hytale.component.Store, String,
 * org.joml.Vector3d, float)}).
 *
 * <p>The proof is the loaded {@code ParticleSystem} asset's own {@code LifeSpan}: a positive value
 * is how long the system lasts in seconds, and zero or less means unlimited (the engine's own
 * documented meaning of the field). A system whose spawners happen to run dry is not provable from
 * the server, so only the {@code LifeSpan} counts, and anything the server cannot read (a blank or
 * unknown id, no asset map loaded) counts as unproven.
 *
 * <p>{@link #provablyEnds} is pure; the other two read the live asset map and never throw.
 */
public final class ParticleLifetimes {

    private ParticleLifetimes() {
    }

    /**
     * PURE: does a system whose own {@code LifeSpan} is {@code lifeSpanSeconds} end on its own?
     * True only for a positive value; zero or less (unlimited) and {@code null} (unknown) are false.
     */
    public static boolean provablyEnds(@Nullable Float lifeSpanSeconds) {
        return lifeSpanSeconds != null && lifeSpanSeconds > 0f;
    }

    /** Whether the loaded system {@code systemId} ends on its own: {@link #provablyEnds} over {@link #lifeSpanOf}. */
    public static boolean systemProvablyEnds(@Nullable String systemId) {
        return provablyEnds(lifeSpanOf(systemId));
    }

    /**
     * The loaded native system's own {@code LifeSpan} in seconds, or {@code null} when it cannot be
     * read (a blank or unknown id, or no asset map loaded), which {@link #provablyEnds} counts as
     * unproven. Never throws.
     */
    @Nullable
    public static Float lifeSpanOf(@Nullable String systemId) {
        if (systemId == null || systemId.isBlank()) {
            return null;
        }
        try {
            ParticleSystem system = ParticleSystem.getAssetMap().getAsset(systemId);
            return system != null ? system.getLifeSpan() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
