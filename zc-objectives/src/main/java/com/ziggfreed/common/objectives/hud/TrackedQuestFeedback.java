package com.ziggfreed.common.objectives.hud;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.progress.runtime.ProgressionFeedbackHook;
import com.ziggfreed.common.subject.Subject;

/**
 * Tells the moment engine which quest moments are about a quest the player can already see on their
 * quest tracker, so none of that quest's toasts are drawn in the corner: the tracker is the notice.
 *
 * <p>The player's level decides how often a quest speaks up, and a quest they keep on screen does not
 * speak up in the corner at all: not for a step, the tick that finishes one, its completion, its parking
 * or its collect. That holds only while the tracker is SHOWING the quest. A quest not painted on it (not
 * pinned, or past the panel's block count), and every quest while the panel is hidden for that player
 * (their Show off, the owner's switch off, the consumer's audience saying no), toasts by the level. With
 * a menu open the tracker is behind it, so a toast drawn into the page (a parked reward collected at an
 * NPC) still shows, graded by the level. Achievements and every other moment pass through untouched.
 *
 * <p><b>The answer is the tracker's own last paint</b> ({@link TrackedQuestHuds#drawing}), the state the
 * panel was drawn from, read as the moment fires. The quest engine fires its native event before this
 * hook, and the tracker's handler for that event only QUEUES a repaint on the world, so a completion or
 * a claim is judged by what the player was looking at when it happened, not by the panel it is about to
 * leave.
 *
 * <p>It only ADDS {@link FeedbackEngine#ON_SCREEN_ARG}; the engine decides, and honours it only for a
 * toast marked {@code PlayerLevel}, so the sound, the banner and the command are never touched and an
 * owner who unmarks a toast gets it back.
 */
public final class TrackedQuestFeedback {

    /** The quest engine's moments that name the quest they are about under {@link #QUEST_ARG}. */
    static final Set<String> QUEST_MOMENTS = Set.of("Quest_Objective_Progressed", "Quest_Completed",
            "Quest_Parked", "Quest_Claimed");

    /** The value those moments carry the quest's id under, the name the quest engine fires them with. */
    private static final String QUEST_ARG = "quest";

    private TrackedQuestFeedback() {
    }

    /** {@code inner}, with each quest moment told whether its quest is on the player's tracker right now. */
    @Nonnull
    public static ProgressionFeedbackHook marking(@Nonnull ProgressionFeedbackHook inner) {
        return new ProgressionFeedbackHook() {

            @Override
            public void fire(@Nonnull String momentId, @Nonnull Subject subject,
                    @Nonnull Map<String, Object> args) {
                inner.fire(momentId, subject, mark(momentId, subject.id(), args));
            }

            @Override
            public boolean listening() {
                return inner.listening();
            }

            @Override
            public boolean answers(@Nonnull String momentId) {
                return inner.answers(momentId);
            }
        };
    }

    /**
     * {@code args} plus {@link FeedbackEngine#ON_SCREEN_ARG} {@code true} when this is a quest moment and
     * its quest is drawn on {@code playerId}'s tracker; otherwise {@code args} itself. Never writes into
     * {@code args}, which the quest engine may hand over read-only.
     */
    @Nonnull
    static Map<String, Object> mark(@Nonnull String momentId, @Nonnull UUID playerId,
            @Nonnull Map<String, Object> args) {
        if (!QUEST_MOMENTS.contains(momentId) || !(args.get(QUEST_ARG) instanceof String questId)
                || !TrackedQuestHuds.drawing(playerId, questId)) {
            return args;
        }
        Map<String, Object> marked = new LinkedHashMap<>(args);
        marked.put(FeedbackEngine.ON_SCREEN_ARG, Boolean.TRUE);
        return marked;
    }
}
