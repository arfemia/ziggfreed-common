package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * When a player last looked at an achievement category, so a category tile can say something new was earned
 * there since. A category reads as new while something in it was earned after {@link #seenAt}; opening it
 * calls {@link #markSeen}. Filled through {@link ObjectiveBookDeps.Builder#seen}; read only through the deps'
 * guarded reads, so a mark that throws costs the marker, never the page.
 */
public interface SeenMarks {

    /** No marks: nothing ever reads as new, and marking remembers nothing. */
    SeenMarks NONE = new SeenMarks() {
        @Override
        public long seenAt(@Nullable Subject subject, @Nonnull String category) {
            return Long.MAX_VALUE;
        }

        @Override
        public void markSeen(@Nullable Subject subject, @Nonnull String category, long nowMs) {
        }
    };

    /** The epoch milliseconds {@code subject} last opened {@code category}; 0 when never. */
    long seenAt(@Nullable Subject subject, @Nonnull String category);

    /** Record that {@code subject} opened {@code category} at {@code nowMs}. */
    void markSeen(@Nullable Subject subject, @Nonnull String category, long nowMs);
}
