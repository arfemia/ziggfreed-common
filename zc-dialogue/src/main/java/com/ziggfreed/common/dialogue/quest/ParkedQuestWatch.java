package com.ziggfreed.common.dialogue.quest;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * Notices a quest that an option's actions just PARKED for collection, so the conversation can take
 * the player to it.
 *
 * <p>A quest parks when its last step is met and it has something waiting to be collected; a quest
 * that pays out on the spot never does. So the signal is the status moving INTO
 * {@link QuestStatus#COMPLETED_UNCLAIMED} across the actions: a snapshot of what was already parked
 * before they ran, and a read of what is parked after. That is deliberately a comparison rather than
 * a listener. The actions are every mod's own handlers, and the one thing that says what they did to
 * this player without becoming a second authority on completion is reading the same state twice.
 *
 * <p>Every read is guarded whole: this runs inside a click handler, where a throw costs the player
 * their screen, and a watch that cannot read simply notices nothing.
 */
public final class ParkedQuestWatch {

    /** The watch of a context nothing can be read for: it notices nothing. */
    private static final ParkedQuestWatch NOTHING = new ParkedQuestWatch(Set.of());

    @Nonnull private final Set<String> parkedBefore;

    private ParkedQuestWatch(@Nonnull Set<String> parkedBefore) {
        this.parkedBefore = parkedBefore;
    }

    /** Take the snapshot, immediately before the option's actions run. */
    @Nonnull
    public static ParkedQuestWatch begin(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
        Subject subject = subjectOrNull(quests, ctx);
        return subject == null ? NOTHING : new ParkedQuestWatch(parked(quests, subject));
    }

    /**
     * The first quest that parked since {@link #begin}, or null when none did. The player is read
     * afresh rather than remembered, so a subject built as a snapshot cannot hide what the actions
     * changed.
     */
    @Nullable
    public String newlyParked(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
        if (this == NOTHING) {
            return null;
        }
        Subject subject = subjectOrNull(quests, ctx);
        if (subject == null) {
            return null;
        }
        for (String questId : parked(quests, subject)) {
            if (!parkedBefore.contains(questId)) {
                return questId;
            }
        }
        return null;
    }

    @Nullable
    private static Subject subjectOrNull(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
        try {
            return quests.subject(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every quest this player has finished and not collected, in the order the reader lists them. */
    @Nonnull
    private static Set<String> parked(@Nonnull DialogueQuests quests, @Nonnull Subject subject) {
        Set<String> out = new LinkedHashSet<>();
        try {
            QuestStateReader reader = quests.reader();
            for (String questId : reader.activeAndUnclaimedIds(subject)) {
                if (reader.status(subject, questId) == QuestStatus.COMPLETED_UNCLAIMED) {
                    out.add(questId);
                }
            }
        } catch (Throwable t) {
            // A reader that cannot answer is a reader with nothing parked: the beat still finishes.
        }
        return out;
    }
}
