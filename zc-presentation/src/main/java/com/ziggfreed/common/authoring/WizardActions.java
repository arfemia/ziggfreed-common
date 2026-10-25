package com.ziggfreed.common.authoring;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * How the authoring wizard page answers an event, apart from the page so it can be held to its one rule: every event
 * is answered exactly once (a rebuild, a partial update, a close or an empty update), whatever happens on the way. A
 * client whose event goes unanswered stays locked. The step does its part and says how to answer; a step that
 * throws, or an answer that throws, falls back to an empty update.
 */
final class WizardActions {

    static final String FIELD = "field";
    static final String PRESS = "press";
    static final String SEARCH = "search";
    static final String CLEAR = "clear";
    static final String BACK = "back";
    static final String NEXT = "next";
    static final String SAVE = "save";
    static final String PICK = "pick";
    static final String CLOSE = "close";

    /** How a step wants the event answered. */
    enum Exit {
        /** Paint the whole page again. */
        REBUILD,
        /** Send the commands the step queued, without a rebuild. */
        PARTIAL,
        /** Close the page. */
        CLOSE,
        /** Send an empty update. */
        ANSWER
    }

    /** The page as the router sees it. */
    interface Host {

        @Nonnull
        Exit field(@Nullable String field, @Nullable String value);

        @Nonnull
        Exit press(@Nullable String field);

        @Nonnull
        Exit search(@Nullable String text);

        @Nonnull
        Exit back();

        @Nonnull
        Exit next();

        @Nonnull
        Exit save();

        @Nonnull
        Exit pick(@Nullable String field);

        void rebuild();

        void partial();

        void close();

        void answer();
    }

    private WizardActions() {
    }

    /** Route one event to its step, then answer it once. */
    static void event(@Nonnull Host host, @Nullable String action, @Nullable String field, @Nullable String value,
            @Nullable String search) {
        Exit exit;
        try {
            exit = switch (action == null ? "" : action) {
                case FIELD -> host.field(field, value);
                case PRESS -> host.press(field);
                case SEARCH -> host.search(search);
                case CLEAR -> host.search("");
                case BACK -> host.back();
                case NEXT -> host.next();
                case SAVE -> host.save();
                case PICK -> host.pick(field);
                case CLOSE -> Exit.CLOSE;
                default -> Exit.ANSWER;
            };
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard page step failed, so the page answers as it stands", t);
            exit = Exit.ANSWER;
        }
        try {
            switch (exit == null ? Exit.ANSWER : exit) {
                case REBUILD -> host.rebuild();
                case PARTIAL -> host.partial();
                case CLOSE -> host.close();
                case ANSWER -> host.answer();
            }
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard page could not answer as it meant to, so it sends an empty update", t);
            try {
                host.answer();
            } catch (Throwable ignored) {
                // Nothing more can reach the client.
            }
        }
    }
}
