package com.ziggfreed.common.instance.metadata;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A generic, immutable telemetry envelope describing one finished instance round (a co-op session,
 * a minigame match, a dungeon clear). PURE DATA: no engine types, no i18n, no hardcoded ids - the
 * consumer supplies every value. It is meant to ride ALONGSIDE a consumer's own native outbound
 * event (e.g. Kweebec's {@code RoundCompletedEvent}) as a flat, stable integration payload another
 * mod can read without depending on the producer's internal types.
 *
 * <p>Field meaning is the consumer's policy:
 * <ul>
 *   <li>{@code modId} - which mod produced the round (a free string, e.g. {@code "kweebec"}).</li>
 *   <li>{@code modeId} - the game mode within that mod, optional.</li>
 *   <li>{@code presetId} - the preset / map / scenario, optional.</li>
 *   <li>{@code difficulty} - a numeric difficulty tier (the consumer's scale; 0 if not used).</li>
 *   <li>{@code playerCount} - how many players were in the round.</li>
 *   <li>{@code durationSeconds} - wall-clock length of the round in seconds.</li>
 *   <li>{@code resultKind} - a free outcome tag (e.g. {@code "win"} / {@code "loss"} / {@code "abandon"}),
 *       optional.</li>
 * </ul>
 *
 * <p>Build it via the all-args canonical constructor, the {@link #of} factory, or the fluent
 * {@link Builder} ({@link #builder(String)}). All optional string fields are {@link Nullable}.
 */
public record RoundMetadata(
        @Nonnull String modId,
        @Nullable String modeId,
        @Nullable String presetId,
        int difficulty,
        int playerCount,
        long durationSeconds,
        @Nullable String resultKind) {

    /**
     * Canonical all-args constructor. {@code modId} must be non-null (the only required field);
     * a null is coerced to the empty string so the envelope never carries a null producer id.
     */
    public RoundMetadata {
        if (modId == null) {
            modId = "";
        }
    }

    /**
     * Static factory mirroring the canonical constructor, for a clean call site where a builder
     * would be overkill.
     */
    @Nonnull
    public static RoundMetadata of(@Nonnull String modId, @Nullable String modeId, @Nullable String presetId,
                                   int difficulty, int playerCount, long durationSeconds,
                                   @Nullable String resultKind) {
        return new RoundMetadata(modId, modeId, presetId, difficulty, playerCount, durationSeconds, resultKind);
    }

    /** Start a fluent build for the given (required) producer mod id. */
    @Nonnull
    public static Builder builder(@Nonnull String modId) {
        return new Builder(modId);
    }

    /**
     * Fluent builder. Every optional field defaults to absent (null string / 0 numeric); only
     * {@code modId} is required (passed to {@link #builder(String)}).
     */
    public static final class Builder {

        @Nonnull
        private final String modId;
        @Nullable
        private String modeId;
        @Nullable
        private String presetId;
        private int difficulty;
        private int playerCount;
        private long durationSeconds;
        @Nullable
        private String resultKind;

        private Builder(@Nonnull String modId) {
            this.modId = modId == null ? "" : modId;
        }

        @Nonnull
        public Builder modeId(@Nullable String modeId) {
            this.modeId = modeId;
            return this;
        }

        @Nonnull
        public Builder presetId(@Nullable String presetId) {
            this.presetId = presetId;
            return this;
        }

        @Nonnull
        public Builder difficulty(int difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        @Nonnull
        public Builder playerCount(int playerCount) {
            this.playerCount = playerCount;
            return this;
        }

        @Nonnull
        public Builder durationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }

        @Nonnull
        public Builder resultKind(@Nullable String resultKind) {
            this.resultKind = resultKind;
            return this;
        }

        /** Materialize the immutable {@link RoundMetadata}. */
        @Nonnull
        public RoundMetadata build() {
            return new RoundMetadata(modId, modeId, presetId, difficulty, playerCount, durationSeconds, resultKind);
        }
    }
}
