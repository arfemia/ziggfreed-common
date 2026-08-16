package com.ziggfreed.common.board;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The real {@link BoardQuests}: a bounty is a quest, and this is where that stops being a slogan.
 *
 * <p>Every question goes straight to the quest engine, and the one thing it adds is the board id as
 * the accept SITE, so the engine's own completion predicate binds a contract to the board it was
 * taken from with no bounty author writing anything.
 *
 * <p>A bounty id nothing answers to is not an error here: the pack that ships the contracts may
 * simply not be installed, so it reads as not carried, never completed, and refuses to be accepted.
 */
public final class QuestEngineBoardQuests implements BoardQuests {

    private final QuestEngine quests;

    public QuestEngineBoardQuests(@Nonnull QuestEngine quests) {
        this.quests = quests;
    }

    /** The engine underneath, for a caller that needs the rest of the lifecycle. */
    @Nonnull
    public QuestEngine engine() {
        return quests;
    }

    @Override
    public boolean accept(@Nonnull Subject subject, @Nonnull String bountyId, @Nonnull String boardId) {
        Quest quest = quests.quest(bountyId);
        return quest != null && quests.accept(subject, quest, boardId);
    }

    @Override
    public boolean isCarried(@Nonnull Subject subject, @Nonnull String bountyId) {
        QuestStatus status = quests.status(subject, bountyId);
        return status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED;
    }

    @Override
    public long lastCompletionMs(@Nonnull Subject subject, @Nonnull String bountyId) {
        QuestProgressStore.CompletionRecord record = quests.store().completions(subject, bountyId);
        return record == null ? 0L : record.lastCompletionMs();
    }

    /**
     * Put the bounty back within reach, keeping its completion record so a lifetime cap still
     * means something. The board engine only asks for this once the completion is genuinely in a
     * past period and the subject is not carrying it, so nothing in progress is ever discarded.
     */
    @Override
    public void reArm(@Nonnull Subject subject, @Nonnull String bountyId) {
        if (isCarried(subject, bountyId)) {
            return;
        }
        quests.clearQuest(subject, bountyId);
    }

    @Override
    @Nullable
    public String acceptedAt(@Nonnull Subject subject, @Nonnull String bountyId) {
        return quests.acceptSiteOf(subject, bountyId);
    }
}
