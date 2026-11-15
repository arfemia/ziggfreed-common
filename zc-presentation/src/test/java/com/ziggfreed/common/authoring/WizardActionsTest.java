package com.ziggfreed.common.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

/**
 * The authoring wizard page answers every event exactly once, whatever happens on the way: each step does its part
 * and answers the way it asked (a rebuild, a partial update, a close), the close button closes, an action nobody
 * knows and a step that throws get an empty update, and an answer that throws falls back to one. A client whose event
 * goes unanswered stays locked.
 */
class WizardActionsTest {

    /** Every way the page can answer, counted, and every step it took. */
    static final class Recorder implements WizardActions.Host {

        @Nonnull WizardActions.Exit asks = WizardActions.Exit.REBUILD;
        boolean stepThrows;
        boolean answerThrows;
        int rebuilds;
        int partials;
        int closes;
        int answers;
        final List<String> steps = new ArrayList<>();

        int exits() {
            return rebuilds + partials + closes + answers;
        }

        @Nonnull
        private WizardActions.Exit step(@Nonnull String step) {
            if (stepThrows) {
                throw new IllegalStateException("step boom");
            }
            steps.add(step);
            return asks;
        }

        @Nonnull
        @Override
        public WizardActions.Exit field(@Nullable String field, @Nullable String value) {
            return step("field " + field + "=" + value);
        }

        @Nonnull
        @Override
        public WizardActions.Exit press(@Nullable String field) {
            return step("press " + field);
        }

        @Nonnull
        @Override
        public WizardActions.Exit search(@Nullable String text) {
            return step("search " + text);
        }

        @Nonnull
        @Override
        public WizardActions.Exit back() {
            return step("back");
        }

        @Nonnull
        @Override
        public WizardActions.Exit next() {
            return step("next");
        }

        @Nonnull
        @Override
        public WizardActions.Exit save() {
            return step("save");
        }

        @Nonnull
        @Override
        public WizardActions.Exit pick(@Nullable String field) {
            return step("pick " + field);
        }

        @Override
        public void rebuild() {
            if (answerThrows) {
                throw new IllegalStateException("rebuild boom");
            }
            rebuilds++;
        }

        @Override
        public void partial() {
            if (answerThrows) {
                throw new IllegalStateException("partial boom");
            }
            partials++;
        }

        @Override
        public void close() {
            if (answerThrows) {
                throw new IllegalStateException("close boom");
            }
            closes++;
        }

        @Override
        public void answer() {
            answers++;
        }
    }

    private static final List<String> STEP_ACTIONS = List.of(WizardActions.FIELD, WizardActions.PRESS,
            WizardActions.SEARCH, WizardActions.CLEAR, WizardActions.BACK, WizardActions.NEXT, WizardActions.SAVE,
            WizardActions.PICK);

    @Nonnull
    private static Recorder run(@Nonnull Recorder page, @Nullable String action) {
        WizardActions.event(page, action, "giver", "Kweebec_Elder", "oak");
        return page;
    }

    @Test
    void eachStepActsThenAnswersTheWayItAsked() {
        assertEquals(List.of("field giver=Kweebec_Elder"), run(new Recorder(), WizardActions.FIELD).steps);
        assertEquals(List.of("press giver"), run(new Recorder(), WizardActions.PRESS).steps);
        assertEquals(List.of("search oak"), run(new Recorder(), WizardActions.SEARCH).steps);
        assertEquals(List.of("search "), run(new Recorder(), WizardActions.CLEAR).steps);
        assertEquals(List.of("pick giver"), run(new Recorder(), WizardActions.PICK).steps);
        for (WizardActions.Exit exit : WizardActions.Exit.values()) {
            for (String action : STEP_ACTIONS) {
                Recorder page = new Recorder();
                page.asks = exit;
                run(page, action);
                assertEquals(1, page.exits(), action + " asking " + exit);
                assertEquals(1, switch (exit) {
                    case REBUILD -> page.rebuilds;
                    case PARTIAL -> page.partials;
                    case CLOSE -> page.closes;
                    case ANSWER -> page.answers;
                }, action + " asking " + exit);
            }
        }
    }

    @Test
    void closeClosesAndAnythingElseGetsAnEmptyUpdate() {
        Recorder closed = run(new Recorder(), WizardActions.CLOSE);
        assertEquals(1, closed.closes);
        assertEquals(1, closed.exits());
        for (String action : new String[] {null, "", "dance"}) {
            Recorder page = run(new Recorder(), action);
            assertEquals(1, page.answers, String.valueOf(action));
            assertEquals(1, page.exits(), String.valueOf(action));
            assertEquals(List.of(), page.steps, String.valueOf(action));
        }
    }

    @Test
    void aStepThatThrowsStillAnswersOnce() {
        for (String action : STEP_ACTIONS) {
            Recorder page = new Recorder();
            page.stepThrows = true;
            run(page, action);
            assertEquals(1, page.answers, action);
            assertEquals(1, page.exits(), action);
        }
    }

    @Test
    void anAnswerThatThrowsFallsBackToAnEmptyUpdate() {
        for (WizardActions.Exit exit : List.of(WizardActions.Exit.REBUILD, WizardActions.Exit.PARTIAL,
                WizardActions.Exit.CLOSE)) {
            Recorder page = new Recorder();
            page.asks = exit;
            page.answerThrows = true;
            run(page, WizardActions.NEXT);
            assertEquals(1, page.answers, String.valueOf(exit));
            assertEquals(1, page.exits(), String.valueOf(exit));
        }
    }
}
