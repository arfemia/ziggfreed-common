package com.ziggfreed.common.dialogue;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.route.Destination;

/**
 * Where a {@code Start} quest row goes once the conversation has decided WHICH quest it is about.
 *
 * <p>It exists for the same reason {@link DialogueTalk} does: the conversation engine reads quest
 * STATE and stops there. What "show me this quest" opens, and how a quest list is told which entry to
 * highlight, belong to the NPC layer one level up, so the engine hands over a quest id and takes back
 * a destination it never has to understand.
 *
 * <p>Ziggfreed Common installs the real router at plugin setup, so a consumer wires nothing. With none
 * installed - a bare unit test, a mod embedding the engine on its own - a row written as
 * {@code "Ready": true} simply does not fire and the ladder carries on, which is the same fail-closed
 * rule every unanswerable question in a conversation follows.
 */
public final class DialogueQuestView {

    private static final AtomicReference<Router> INSTALLED = new AtomicReference<>();

    private DialogueQuestView() {
    }

    /** What turns "this quest, at this character" into something that can be opened. */
    @FunctionalInterface
    public interface Router {

        /**
         * The destination a quest row fires.
         *
         * @param authored what the row wrote, or null for the default (this character's quest list)
         * @param questId  the row's own quest, which a quest list highlights
         * @return the destination to open, or null when there is nothing to open
         */
        @Nullable
        Destination route(@Nullable Destination authored, @Nonnull String questId);
    }

    /** Install the router every quest row fires through. Called once, from plugin setup. */
    public static void install(@Nullable Router router) {
        INSTALLED.set(router);
    }

    /** Is anything installed to route through? */
    public static boolean installed() {
        return INSTALLED.get() != null;
    }

    /**
     * Resolve what a quest row opens, or null when nothing can. Guarded whole: a router that throws
     * costs its own beat and the ladder carries on to the next one.
     */
    @Nullable
    public static Destination route(@Nullable Destination authored, @Nullable String questId) {
        if (questId == null || questId.isBlank()) {
            return authored;
        }
        Router router = INSTALLED.get();
        if (router == null) {
            return authored;
        }
        try {
            return router.route(authored, questId.trim());
        } catch (Throwable t) {
            return authored;
        }
    }
}
