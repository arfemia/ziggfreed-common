package com.ziggfreed.common.objectives.journal;

import javax.annotation.Nonnull;

import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.ui.kit.MetaGlyph;
import com.ziggfreed.common.ui.kit.RowAccent;

/**
 * What kind of quest a list row says it is, from what the library already knows and the one fact an author writes:
 * a contract a board posts is a bounty, a quest with a {@code Repeat} rule comes round again, a quest marked
 * {@code Listing.MainLine} is a main quest, and anything else is a side quest, in that order. One exception: a main
 * quest whose only repeat is once per run of a calendar event ({@link Quest.Repeat#oncePerRunOnly}, a yearly story)
 * is still a main quest, since the story is the point and the event brings it round; a main quest with a rolling
 * wait or a periodic reset (a daily) stays repeatable. The kind picks the
 * glyph the row's meta line opens with, and a main quest wears the gold bar and lists first in its section; every
 * other kind leaves the bar off, since the row's state pill already carries its tone.
 */
public enum QuestKind {

    /** A board's contract: a clock, since the board posts new work on its turnover. */
    BOUNTY(MetaGlyph.CLOCK),
    /** A quest that comes round again (daily, weekly, seasonal, or whenever it is done): a clock. */
    REPEATABLE(MetaGlyph.CLOCK),
    /** An authored main quest: a gold star, the gold bar, first in its section. */
    MAIN(MetaGlyph.STAR),
    /** Anything else: a scroll. */
    SIDE(MetaGlyph.SCROLL);

    private final MetaGlyph glyph;

    QuestKind(@Nonnull MetaGlyph glyph) {
        this.glyph = glyph;
    }

    /** {@code q}'s kind; {@code boardManaged} is whether a board posts it ({@link QuestPresentation#managed}). */
    @Nonnull
    public static QuestKind of(@Nonnull Quest q, boolean boardManaged) {
        if (boardManaged) {
            return BOUNTY;
        }
        Quest.Repeat repeat = q.repeat();
        if (repeat != null && !(q.mainLine() && repeat.oncePerRunOnly())) {
            return REPEATABLE;
        }
        return q.mainLine() ? MAIN : SIDE;
    }

    /**
     * Whether {@code q} lists first in its section, as a main quest. Asked without the board's answer, since a
     * board's contract never carries {@code MainLine} (a bounty folds none), so it reads as {@link #of} does.
     */
    public static boolean listsFirst(@Nonnull Quest q) {
        return of(q, false) == MAIN;
    }

    /** The glyph the row's meta line opens with. */
    @Nonnull
    public MetaGlyph glyph() {
        return glyph;
    }

    /** What the row's accent bar shows: the gold bar for a main quest, none for the rest. */
    @Nonnull
    public RowAccent accent() {
        return this == MAIN ? RowAccent.FEATURED : RowAccent.NONE;
    }
}
