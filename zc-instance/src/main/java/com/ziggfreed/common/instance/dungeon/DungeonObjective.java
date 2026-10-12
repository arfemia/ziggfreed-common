package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A dungeon's {@code Complete.Objective} as a run reads it: the moment a member must meet inside the area, in the
 * words a quest step is written in, and how many the party needs. This module cannot see the moment stream, so it
 * only holds the words: zc-objectives matches each produced moment against them through a moment listener and
 * counts a match with {@link DungeonRuns#countObjective}.
 *
 * @param kind      the objective kind, as written (an alias a pack registered reads as the kind it runs as)
 * @param target    which one, as written; empty for any
 * @param matchMode how the target is compared, as written ({@code EXACT}, {@code CONTAINS}, {@code PREFIX}); null
 *                  for the quest step's own default
 * @param qualifier the kind's secondary filter, compared whole; null for any
 * @param amount    how many the party needs in all, at least 1
 */
public record DungeonObjective(@Nonnull String kind, @Nonnull String target, @Nullable String matchMode,
                               @Nullable String qualifier, long amount) {
}
