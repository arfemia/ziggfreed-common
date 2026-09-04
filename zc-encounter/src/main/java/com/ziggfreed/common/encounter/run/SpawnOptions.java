package com.ziggfreed.common.encounter.run;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a caller asks for when it spawns an encounter from Java.
 *
 * @param ownerKey         who owns the run (a round id, say), or null for a world boss nobody owns
 * @param difficulty       the difficulty label the run carries, or null for the binding row's own
 * @param healthMultiplier a run multiplier composed into the binding's health scale (1 = none)
 * @param showMarker       whether the creative marker model is attached to the encounter entity
 * @param seedMembers      players stamped as members every tick, so a whole party gets the bar and
 *                         the music regardless of where its members stand
 */
public record SpawnOptions(@Nullable String ownerKey, @Nullable String difficulty, double healthMultiplier,
                           boolean showMarker, @Nonnull List<UUID> seedMembers) {

    public SpawnOptions {
        healthMultiplier = Double.isFinite(healthMultiplier) && healthMultiplier > 0.0 ? healthMultiplier : 1.0;
        seedMembers = List.copyOf(seedMembers);
    }

    /** No owner, no label, no multiplier, no marker, nobody seeded: a world boss placed by hand. */
    @Nonnull
    public static SpawnOptions defaults() {
        return new SpawnOptions(null, null, 1.0, false, List.of());
    }

    /** A round's encounter: owned by the round, at its difficulty and multiplier, with its party seeded. */
    @Nonnull
    public static SpawnOptions forRound(@Nonnull String roundId, @Nullable String difficulty,
            double healthMultiplier, @Nonnull List<UUID> party) {
        return new SpawnOptions(roundId, difficulty, healthMultiplier, false, party);
    }

    @Nonnull
    public SpawnOptions withMarker(boolean marker) {
        return new SpawnOptions(ownerKey, difficulty, healthMultiplier, marker, seedMembers);
    }

    @Nonnull
    public SpawnOptions withDifficulty(@Nullable String label) {
        return new SpawnOptions(ownerKey, label, healthMultiplier, showMarker, seedMembers);
    }
}
