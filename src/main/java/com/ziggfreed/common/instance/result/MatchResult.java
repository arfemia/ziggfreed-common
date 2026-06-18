package com.ziggfreed.common.instance.result;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The immutable snapshot the {@link com.ziggfreed.common.instance.result.ResultsPage}
 * renders: the outcome, the duration, the team-grouped score breakdown, the granted /
 * pending reward chips, and the leaderboard bucket the "View Leaderboard" CTA deep-links
 * into. The consumer builds this at its resolve / teardown choke-point (Kweebec at
 * {@code RoundService.resolve()}, from its existing per-player {@code PlayerScore} map)
 * INSIDE the resolve window, before the in-memory round state is torn down.
 */
public record MatchResult(@Nonnull ResultKind kind, int durationSeconds, @Nonnull List<TeamResult> teams,
                          int difficultyScore, @Nonnull List<RewardChip> rewards,
                          @Nullable String leaderboardBucket) {

    public MatchResult {
        teams = List.copyOf(teams);
        rewards = List.copyOf(rewards);
    }
}
