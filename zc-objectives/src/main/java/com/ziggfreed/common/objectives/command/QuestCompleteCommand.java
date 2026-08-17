package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code quest complete}: close a quest out for a player and pay it, whether or not its steps were
 * met - the tutorial skip, the stuck quest an administrator settles by hand.
 *
 * <p>It is the engine's {@link QuestEngine#forceComplete}, under the registered scope, so it sounds
 * and settles exactly as a completion from the owning mod's own surface would. The engine's own rule
 * makes it idempotent: a one-shot already finished is left alone and NOT paid twice, so a double
 * press cannot double-grant, and this verb says so rather than reporting a payout that did not
 * happen.
 *
 * <p><b>The player has to be online</b>, like every per-player verb here. A reward owed to somebody
 * who is away needs a spool keyed by an identity the runtime does not keep - the directory of
 * everyone who has ever connected is a consumer's own - so a consumer that has one offers the
 * offline form through its own command, and this one refuses honestly rather than paying into
 * nowhere.
 */
final class QuestCompleteCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> questArg;

    QuestCompleteCommand() {
        super(ProgressCommandLine.Quest.GROUP, ProgressCommandLine.Quest.COMPLETE);
        this.questArg = withOptionalArg("quest", ProgressAdminMessages.desc("arg.quest"),
                ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Quest quest = ContentArgs.quest(ctx, questArg);
        if (quest == null) {
            return;
        }
        Subject subject = questSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        QuestEngine engine = ProgressionRuntime.quests();
        boolean closed = Boolean.TRUE.equals(ProgressionRuntime.questScope().around(subject,
                s -> Boolean.valueOf(engine.forceComplete(s, quest))));
        if (closed) {
            ProgressAdminMessages.done(ctx, "quest.completed", quest.id(), target.name());
        } else {
            ProgressAdminMessages.refused(ctx, "quest.complete.already", quest.id(), target.name());
        }
    }
}
