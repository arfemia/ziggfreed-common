package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.QuestCadence;

/**
 * "This finished quest's completion carries THAT qualifier." The seam the library's
 * quest-completion producer asks when a quest's reward is collected, so the one
 * {@code COMPLETE_QUEST} moment it fires can carry a qualifier the quest's own repeat rule cannot
 * say: a contract a board posts every day holds no clock of its own, so its quest reads repeatable,
 * and only the owner of the board knows it comes round daily.
 *
 * <p>With nothing registered, or nothing answering, the moment carries the quest's own cadence
 * word ({@link QuestCadence#qualifier()}), which is right for every quest whose repeat rule is the
 * whole story. An answer replaces that word on the ONE moment the completion produces; there is
 * never a second, re-qualified fire, which would count one completion twice for every criterion
 * that authors no qualifier.
 *
 * <p><b>Contributions STACK, first real answer wins.</b> Every registered qualifier is asked in
 * registration order and the first non-null answer stands, so two mods that each know their own
 * kind of quest never have to know the other exists; one that THROWS is skipped with a warn and the
 * next is asked.
 *
 * <p>Asked on the world thread, inside the claim that paid the quest out. Answer null for a quest
 * this contribution knows nothing about.
 */
@FunctionalInterface
public interface QuestCompletionQualifier {

    /** Answers for nothing: the composed answer on a runtime nobody registered one into. */
    QuestCompletionQualifier NONE = questId -> null;

    /**
     * The qualifier this finished quest's completion carries, or null when this contribution does
     * not know one.
     *
     * @param questId the quest whose reward was just collected
     */
    @Nullable
    String qualifierFor(@Nonnull String questId);
}
