package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.objectives.admin.QuestAdminOps;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code quest reset}: an administrator starting a player over on one quest, or on all of them.
 *
 * <p>A WIPE, not the in-play re-arm. Abandoning, a repeatable coming round and a lapsed contract
 * re-offered all keep the record of how often the player has finished the quest, because a lifetime
 * cap either of them wiped could never be reached; an administrator's "start over" drops it, which
 * is what {@link QuestEngine#wipeQuest} exists to say. Either way the re-arm is reported, so a memory
 * a conversation declared to live only as long as the quest is forgotten with it.
 *
 * <p>{@code --quest=all} wipes every quest the store knows for the player and then sweeps the whole
 * quest-scoped dialogue namespace, because a conversation can remember something about a quest the
 * player never took, filed under an id their quest state has no record of, and a per-id sweep walks
 * straight past it. It is deliberately NOT the total memory clear: a greeting a character remembers
 * giving, a name a player told somebody, a one-shot gift already taken are not quest progress, and
 * {@code memory forget} is the verb that means all of it.
 */
final class QuestResetCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> questArg;

    QuestResetCommand() {
        super(ProgressCommandLine.Quest.GROUP, ProgressCommandLine.Quest.RESET);
        this.questArg = withOptionalArg("quest", ProgressAdminMessages.desc("arg.quest_or_all"),
                ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String questId = ContentArgs.value(ctx, questArg);
        if (questId == null) {
            ProgressAdminMessages.refused(ctx, "quest.needed");
            return;
        }
        Subject subject = questSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        QuestEngine engine = ProgressionRuntime.quests();
        if (ProgressCommandLine.ALL.equalsIgnoreCase(questId)) {
            int wiped = QuestAdminOps.resetAll(subject, target.store(), target.ref(),
                    target.playerRef());
            ProgressAdminMessages.done(ctx, "quest.reset.all", target.name(), wiped);
            return;
        }
        // A record whose definition has gone (an orphan) is exactly what an administrator may be
        // here to clear, so an id the catalogue no longer knows is still wiped when the player
        // carries a record under it; only an id NEITHER side knows is a typo.
        Quest quest = engine.quest(questId);
        if (quest == null && !engine.store().knownQuestIds(subject).contains(questId)) {
            ProgressAdminMessages.unknownQuest(ctx, questId);
            return;
        }
        QuestAdminOps.resetOne(subject, questId, target.playerRef());
        ProgressAdminMessages.done(ctx, "quest.reset.one", questId, target.name());
    }

}
