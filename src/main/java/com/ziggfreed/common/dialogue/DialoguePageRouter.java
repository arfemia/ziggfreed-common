package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * Maps a dialogue {@code OpenPage} target to opening some page. Returns true when
 * a page was opened, in which case the dialogue page must NOT re-open itself (the
 * router already replaced it). The consumer supplies the routing table; the
 * default routes nowhere (an {@code OpenPage} option just re-renders its node).
 */
@FunctionalInterface
public interface DialoguePageRouter {

    boolean open(@Nonnull String target, @Nonnull DialogueExecContext ctx);

    /** MVP default: routes nowhere. */
    DialoguePageRouter NONE = (target, ctx) -> false;
}
