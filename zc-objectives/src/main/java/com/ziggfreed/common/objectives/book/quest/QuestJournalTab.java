package com.ziggfreed.common.objectives.book.quest;

import javax.annotation.Nonnull;

import com.ziggfreed.common.objectives.book.BookContext;
import com.ziggfreed.common.objectives.book.BookTab;
import com.ziggfreed.common.objectives.book.ObjectiveBookEventData;
import com.ziggfreed.common.objectives.book.ObjectiveBookMenu;
import com.ziggfreed.common.objectives.book.ObjectiveBookPage;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.Picture;

/**
 * The book's Quests tab. For now its document holds one empty state and the tab hands every event to the shell,
 * which answers it; the journal (sections by state, the quest's page, the action bar) replaces this body on the
 * same seams (W1-58 plan, section 3.12): its document is {@link #DOCUMENT}, its root {@link BookContext#body()}.
 * The shell builds one instance per page with this no-argument constructor.
 */
public final class QuestJournalTab implements BookTab {

    /** The tab's document, appended into the shell's {@code #TabBody}. */
    public static final String DOCUMENT = "Pages/ZigBookJournal.ui";

    /** The empty state inside it. */
    static final String EMPTY = "#JournalEmpty";

    @Nonnull
    @Override
    public String id() {
        return ObjectiveBookPage.TAB_QUESTS;
    }

    @Nonnull
    @Override
    public String document() {
        return DOCUMENT;
    }

    @Override
    public void build(@Nonnull BookContext ctx) {
        EmptyStatePainter.paint(ctx.cmd(), ctx.events(), ctx.at(EMPTY), new EmptyState(
                Picture.item(ObjectiveBookMenu.QUESTS_ICON), ctx.text("book.placeholder"), null, null), null);
    }

    @Override
    public boolean handle(@Nonnull BookContext ctx, @Nonnull String action, @Nonnull ObjectiveBookEventData data) {
        return false;
    }
}
