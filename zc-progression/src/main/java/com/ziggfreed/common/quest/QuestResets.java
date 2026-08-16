package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Who wants to know that a quest was RE-ARMED, and the seam they are told through.
 *
 * <p>Clearing a quest is not only a progression fact. State elsewhere is declared to live and die
 * with a quest - a conversation's memory of what it already said about it, above all - and the layer
 * holding that state sits ABOVE this module in the dependency graph, so this module can never call
 * it. That is what this seam is for: the engine says WHAT happened in its own vocabulary, and the
 * wiring root joins it to whoever can act on it.
 *
 * <p><b>Every re-arm is reported, not only an abandon.</b> A repeatable coming back round, a lapsed
 * board contract re-offered and a quest given up are all the same event to anything keyed on the
 * quest: the player is about to be able to play it again. Reporting one and not the others would
 * leave a declared lifetime honoured on some paths and quietly ignored on the rest.
 *
 * <p>A listener is a courtesy, exactly like the outbound events: it is called on the caller's own
 * thread, its failures are logged rather than propagated, and nothing about the re-arm depends on
 * it. With none installed - a unit JVM, a server whose library half is not wired - the engine
 * behaves exactly as it always did.
 */
public final class QuestResets {

    /** What a re-arm is reported to. One listener; the wiring root is the one caller of install. */
    @FunctionalInterface
    public interface Listener {

        /**
         * This player's quest was re-armed: its status, progress, cooldown stamp and pin are gone
         * and they may take it again. Whatever else was declared to live only as long as that quest
         * is this listener's to forget.
         */
        void onQuestCleared(@Nonnull Subject subject, @Nonnull String questId);
    }

    @Nullable
    private static volatile Listener listener;

    private QuestResets() {
    }

    /** Install the listener. Called once, from the wiring root's {@code setup()}. */
    public static void install(@Nonnull Listener installed) {
        listener = installed;
    }

    /** Forget the installed listener (a test reset, and shutdown). */
    public static void reset() {
        listener = null;
    }

    /**
     * Report a re-arm. Guarded end to end: a listener that throws must never take a quest reset
     * down with it, since the reset itself has already happened by the time this is called.
     */
    public static void fire(@Nonnull Subject subject, @Nonnull String questId) {
        Listener target = listener;
        if (target == null) {
            return;
        }
        try {
            target.onQuestCleared(subject, questId);
        } catch (Throwable t) {
            SafeLog.warn("[quest] a listener failed on the re-arm of quest '" + questId + "'", t);
        }
    }
}
