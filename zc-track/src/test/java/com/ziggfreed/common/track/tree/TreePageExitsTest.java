package com.ziggfreed.common.track.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.kit.ActionSlot;

/**
 * The tree page answers every event exactly once, whatever happens on the way: a tab, a row and each of the three
 * buttons do their part and then repaint, a step that throws still repaints, the close button closes, anything else
 * and an event nobody can be read for get an empty update, and a repaint that throws falls back to one. A client whose
 * event goes unanswered stays locked. And the picks made on the page stop being undoable when it goes, by any way but
 * its own repaint.
 */
class TreePageExitsTest {

    /** Every way the page can answer, counted, and every step it took. */
    static final class Recorder implements TreeActions.Host<Object> {

        @Nullable Object player = new Object();
        boolean lookupThrows;
        boolean stepThrows;
        boolean reopenThrows;
        int reopens;
        int closes;
        int answers;
        final List<String> steps = new ArrayList<>();

        int exits() {
            return reopens + closes + answers;
        }

        @Nullable
        @Override
        public Object player() {
            if (lookupThrows) {
                throw new IllegalStateException("store gone");
            }
            return player;
        }

        @Override
        public void tab(@Nonnull Object who, @Nonnull String tab) {
            step("tab " + tab);
        }

        @Override
        public void select(@Nonnull Object who, @Nonnull String row) {
            step("select " + row);
        }

        @Override
        public void press(@Nonnull Object who, @Nonnull ActionSlot slot) {
            step("press " + slot);
        }

        private void step(@Nonnull String step) {
            if (stepThrows) {
                throw new IllegalStateException("take boom");
            }
            steps.add(step);
        }

        @Override
        public void reopen(@Nonnull Object who) {
            if (reopenThrows) {
                throw new IllegalStateException("reopen boom");
            }
            reopens++;
        }

        @Override
        public void close(@Nonnull Object who) {
            closes++;
        }

        @Override
        public void answer() {
            answers++;
        }
    }

    @Nonnull
    private static Recorder run(@Nonnull Recorder page, @Nullable String action, @Nullable String tab,
            @Nullable String row) {
        TreeActions.event(page, action, tab, row);
        return page;
    }

    @Test
    void eachStepActsThenRepaintsOnce() {
        assertEquals(List.of("tab branch/guard"),
                run(new Recorder(), TreeActions.TAB, " branch/guard ", null).steps);
        assertEquals(List.of("select node/zc_track_example_tree_stand"),
                run(new Recorder(), TreeActions.SELECT, null, "node/zc_track_example_tree_stand").steps);
        assertEquals(List.of("press PRIMARY"), run(new Recorder(), TreeActions.PRIMARY, null, null).steps);
        assertEquals(List.of("press SECONDARY"), run(new Recorder(), TreeActions.SECONDARY, null, null).steps);
        assertEquals(List.of("press DANGER"), run(new Recorder(), TreeActions.DANGER, null, null).steps);
        for (String action : List.of(TreeActions.TAB, TreeActions.SELECT, TreeActions.PRIMARY,
                TreeActions.SECONDARY, TreeActions.DANGER)) {
            Recorder page = run(new Recorder(), action, "builds", "build/1");
            assertEquals(1, page.reopens, action);
            assertEquals(1, page.exits(), action);
        }
    }

    @Test
    void aTabOrARowNamingNothingStillRepaintsOnce() {
        for (String[] click : new String[][] {{TreeActions.TAB, null, null}, {TreeActions.TAB, " ", null},
                {TreeActions.SELECT, null, null}, {TreeActions.SELECT, null, ""}}) {
            Recorder page = run(new Recorder(), click[0], click[1], click[2]);
            assertEquals(List.of(), page.steps, "a click naming nothing does nothing");
            assertEquals(1, page.reopens);
            assertEquals(1, page.exits());
        }
    }

    @Test
    void aStepThatThrowsStillRepaintsOnce() {
        for (String action : List.of(TreeActions.TAB, TreeActions.SELECT, TreeActions.PRIMARY,
                TreeActions.SECONDARY, TreeActions.DANGER)) {
            Recorder page = new Recorder();
            page.stepThrows = true;
            run(page, action, "builds", "respec");
            assertEquals(1, page.reopens, action);
            assertEquals(1, page.exits(), action);
        }
    }

    @Test
    void aRepaintThatThrowsFallsBackToOneEmptyUpdate() {
        Recorder page = new Recorder();
        page.reopenThrows = true;
        run(page, TreeActions.PRIMARY, null, null);
        assertEquals(List.of("press PRIMARY"), page.steps);
        assertEquals(1, page.answers);
        assertEquals(1, page.exits());
    }

    @Test
    void closeClosesOnceAndStepsNothing() {
        Recorder page = run(new Recorder(), TreeActions.CLOSE, "builds", "respec");
        assertEquals(1, page.closes);
        assertEquals(1, page.exits());
        assertEquals(List.of(), page.steps);
    }

    @Test
    void anythingElseGetsOneEmptyUpdate() {
        for (String action : new String[] {null, "", " ", "no_such_thing", "claim"}) {
            Recorder page = run(new Recorder(), action, "builds", "respec");
            assertEquals(1, page.answers, String.valueOf(action));
            assertEquals(1, page.exits(), String.valueOf(action));
            assertEquals(List.of(), page.steps, String.valueOf(action));
        }
    }

    @Test
    void anEventNobodyCanBeReadForGetsOneEmptyUpdateAndStepsNothing() {
        Recorder none = new Recorder();
        none.player = null;
        run(none, TreeActions.PRIMARY, null, null);
        assertEquals(1, none.answers);
        assertEquals(1, none.exits());
        assertEquals(List.of(), none.steps);

        Recorder throwing = new Recorder();
        throwing.lookupThrows = true;
        run(throwing, TreeActions.SELECT, null, "node/x");
        assertEquals(1, throwing.answers);
        assertEquals(1, throwing.exits());
        assertEquals(List.of(), throwing.steps);
    }

    @Test
    void theUndoWindowClosesWithThePageButNeverOnItsOwnRepaint() {
        AtomicInteger closed = new AtomicInteger();
        TreeActions.Window window = new TreeActions.Window(closed::incrementAndGet);

        // The engine dismisses the page before it builds it again, inside the page's own repaint.
        window.reopen(window::dismissed);
        assertEquals(0, closed.get(), "a repaint keeps the picks undoable");

        window.dismissed();
        assertEquals(1, closed.get(), "a close, the client's dismiss or another page ends them");

        assertThrows(IllegalStateException.class, () -> window.reopen(() -> {
            throw new IllegalStateException("reopen boom");
        }));
        window.dismissed();
        assertEquals(2, closed.get(), "a repaint that threw leaves the next dismiss closing the window");
    }

    @Test
    void thePageAnswersAndClosesItsWindowThroughTheTestedDoors() throws IOException {
        String page = Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "track", "tree",
                "TreePage.java"), StandardCharsets.UTF_8);
        int handler = page.indexOf("public void handleDataEvent(");
        assertTrue(handler > 0 && page.indexOf("TreeActions.event(", handler) > handler,
                "handleDataEvent answers through TreeActions.event, the path these tests drive");
        int dismiss = page.indexOf("public void onDismiss(");
        assertTrue(dismiss > 0 && page.indexOf("window.dismissed()", dismiss) > dismiss,
                "a dismiss ends the undo window through the tested Window");
        int reopen = page.indexOf("public void reopen(");
        assertTrue(reopen > 0 && page.indexOf("window.reopen(", reopen) > reopen,
                "the page's own repaint goes through the tested Window, so it keeps the picks undoable");
        assertTrue(page.contains("PickService.closeUndo("), "the window's close is the server's own undo close");
    }
}
