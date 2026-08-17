package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

/**
 * Which of the runtime's two peer systems a per-player question is about.
 *
 * <p>There are exactly two engines on a server and they are peers, so an answer that concerns one
 * of them has to say which. Naming them in an enum rather than in two parallel methods is what lets
 * a gate be written once and applied to both, and what keeps a caller from silently asking the
 * quest question and applying the answer to achievements.
 */
public enum ProgressionSystem {

    /** The accept / progress / hand-in / claim lifecycle. */
    QUEST("quest"),

    /** The always-on peer: nothing is accepted, every criterion listens from the first event. */
    ACHIEVEMENT("achievement");

    private final String label;

    ProgressionSystem(@Nonnull String label) {
        this.label = label;
    }

    /** The lower-case word this system is called in a log line. */
    @Nonnull
    public String label() {
        return label;
    }
}
