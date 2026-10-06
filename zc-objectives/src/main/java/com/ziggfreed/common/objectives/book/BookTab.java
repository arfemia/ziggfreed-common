package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One of the book's tabs, painted inside the shell. The shell owns the frame, the rail, the header band, the
 * toasts and the answer to every event; a tab owns its document (appended into the shell's {@code #TabBody}
 * before {@link #build} runs, its root addressed as {@link BookContext#body()}) and what happens inside it.
 *
 * <p>A tab hears every event first except a close and a consumer's {@code ext} click. Returning true says the
 * tab took it: it answers through the context ({@link BookContext#sendPartial}, {@link BookContext#reopen}) and
 * records any state a partial changed with {@link BookContext#keep}; a tab that returns true without answering
 * is answered with an empty update. Returning false hands the event to the shell, which answers every
 * {@link BookActions} action by reopening on the next state, so a tab need take only what it can show better
 * than a reopen (a selection, a section toggle, a pin, a claim that moves nothing).
 */
public interface BookTab {

    /** {@link ObjectiveBookPage#TAB_QUESTS} or {@link ObjectiveBookPage#TAB_ACHIEVEMENTS}. */
    @Nonnull
    String id();

    /** The document the shell appends into {@code #TabBody}, a path under {@code Common/UI/Custom/}. */
    @Nonnull
    String document();

    /** Paint the tab into a full build. Never throws past the shell, which logs and keeps the page. */
    void build(@Nonnull BookContext ctx);

    /** Take {@code action} (true) or hand it to the shell (false). */
    boolean handle(@Nonnull BookContext ctx, @Nonnull String action, @Nonnull ObjectiveBookEventData data);

    /**
     * The tab's search row, relative to its document's root (for example {@code "#Search"}), whose live text
     * every {@link BookContext#binding} carries; null for a tab with none.
     */
    @Nullable
    default String searchRow() {
        return null;
    }
}
