package com.ziggfreed.common.track.tree;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.util.SafeLog;

/**
 * How the tree page ({@link TreePage}) answers each event, apart from the page so a unit JVM drives it: every event
 * gets exactly one answer, since a client whose event goes unanswered stays locked. A tab or a row picked, and a press
 * of one of the reading page's three buttons, each do their part and then repaint the page on what now stands, so a
 * press that throws, or a stale row, still repaints and shows the truth; a close closes it; anything else (an empty
 * action, one this page never binds) gets an empty update. A player who cannot be read is answered with an empty
 * update too, and an answer that throws falls back to one.
 *
 * <p>The page's undo window ({@link Window}) closes with the page: on its close, on the client's dismiss, and when
 * another page takes its place, but never on its own repaint.
 */
final class TreeActions {

    /** The frame's close button. */
    static final String CLOSE = "close";

    /** A tab's click ({@code Tab} on the event). */
    static final String TAB = "tab";

    /** A row's click ({@code Row} on the event). */
    static final String SELECT = "select";

    /** The reading page's main button, on whatever row it shows. */
    static final String PRIMARY = "primary";

    /** The reading page's second button. */
    static final String SECONDARY = "secondary";

    /** The reading page's danger button. */
    static final String DANGER = "danger";

    /** Every action the page binds. */
    static final List<String> ALL = List.of(CLOSE, TAB, SELECT, PRIMARY, SECONDARY, DANGER);

    /** What the page does for an event: its steps, and its three ways to answer. */
    interface Host<P> {

        /** The player the event came from, or null when there is none to answer for. */
        @Nullable
        P player();

        /** Show the tab {@code tab}. */
        void tab(@Nonnull P player, @Nonnull String tab);

        /** Read the row {@code row}. */
        void select(@Nonnull P player, @Nonnull String row);

        /** Press the button in {@code slot} on the row the page shows (it may only arm, or raise a toast). */
        void press(@Nonnull P player, @Nonnull ActionSlot slot);

        /** Repaint the page on what now stands: an answer. */
        void reopen(@Nonnull P player);

        /** Close the page: an answer. */
        void close(@Nonnull P player);

        /** An empty update: an answer that changes nothing. */
        void answer();
    }

    /**
     * When the picks made on one tree page stop being undoable: whenever the page is dismissed (its close, the
     * client's own dismiss, another page opened over it), except by its own repaint, which the engine also runs as a
     * dismiss before it builds the page again. World thread, like every page event and dismiss.
     */
    static final class Window {

        @Nonnull private final Runnable closeUndo;
        private boolean reopening;

        Window(@Nonnull Runnable closeUndo) {
            this.closeUndo = closeUndo;
        }

        /** Run the page's own repaint: the dismiss it causes keeps the undo window open. */
        void reopen(@Nonnull Runnable reopen) {
            reopening = true;
            try {
                reopen.run();
            } finally {
                reopening = false;
            }
        }

        /** The page was dismissed: unless by its own repaint, nothing made on it can be undone any more. */
        void dismissed() {
            if (!reopening) {
                closeUndo.run();
            }
        }
    }

    private TreeActions() {
    }

    /** Answer one event on {@code page}, exactly once. Never throws. */
    static <P> void event(@Nonnull Host<P> page, @Nullable String action, @Nullable String tab,
            @Nullable String row) {
        P player;
        try {
            player = page.player();
        } catch (Throwable t) {
            SafeLog.warn("[tree] the tree page could not read who clicked: " + t.getMessage());
            answer(page);
            return;
        }
        if (player == null) {
            answer(page);
            return;
        }
        String act = action == null ? "" : action.trim();
        try {
            if (CLOSE.equals(act)) {
                page.close(player);
            } else if (ALL.contains(act)) {
                step(page, player, act, tab, row);
                page.reopen(player);
            } else {
                page.answer();
            }
        } catch (Throwable t) {
            SafeLog.warn("[tree] the tree page could not answer '" + act + "': " + t.getMessage());
            answer(page);
        }
    }

    /** One step; a tab or a row naming nothing does nothing. A step that throws is logged, never raised. */
    private static <P> void step(@Nonnull Host<P> page, @Nonnull P player, @Nonnull String act,
            @Nullable String tab, @Nullable String row) {
        try {
            switch (act) {
                case TAB -> {
                    if (tab != null && !tab.isBlank()) {
                        page.tab(player, tab.trim());
                    }
                }
                case SELECT -> {
                    if (row != null && !row.isBlank()) {
                        page.select(player, row.trim());
                    }
                }
                case PRIMARY -> page.press(player, ActionSlot.PRIMARY);
                case SECONDARY -> page.press(player, ActionSlot.SECONDARY);
                case DANGER -> page.press(player, ActionSlot.DANGER);
                default -> {
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[tree] a step on the tree page failed; the page shows what stands", t);
        }
    }

    /** The empty update, its own throw only logged: nothing is left to answer with. */
    private static void answer(@Nonnull Host<?> page) {
        try {
            page.answer();
        } catch (Throwable t) {
            SafeLog.warn("[tree] the tree page could not answer an event: " + t.getMessage());
        }
    }
}
