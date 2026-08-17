package com.ziggfreed.common.objectives.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.questlist.ProgressionTexts;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestLifecycle;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code quest status}: where every quest the store has a record of stands for one player, with the
 * steps of anything they are carrying or have parked for collection.
 *
 * <p>The status a row leads with is the EFFECTIVE one - what every surface shows the player - and
 * the stored one is added only where the two differ, which is the case an administrator is usually
 * chasing: a finished repeatable reading as offerable again, or one still held back by its clock or
 * its calendar window. The wait shown is the WHOLE wait, so a quest held by a window reads its real
 * boundary rather than the zero its rolling clock would report.
 *
 * <p>An id whose definition has gone is shown as an orphan rather than skipped, because a quest a
 * player is stuck on with no definition to progress it is exactly what somebody came here to find.
 */
final class QuestStatusCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> questArg;

    QuestStatusCommand() {
        super(ProgressCommandLine.Quest.GROUP, ProgressCommandLine.Quest.STATUS);
        this.questArg = withOptionalArg("quest", ProgressAdminMessages.desc("arg.quest_filter"),
                ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Subject subject = questSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        String only = ContentArgs.value(ctx, questArg);
        QuestEngine engine = ProgressionRuntime.quests();
        ProgressAdminMessages.heading(ctx, "quest.status.header", target.name());
        int shown = 0;
        for (String questId : new TreeSet<>(engine.store().knownQuestIds(subject))) {
            if (only != null && !only.equalsIgnoreCase(questId)) {
                continue;
            }
            row(ctx, engine, subject, questId);
            shown++;
        }
        if (shown == 0) {
            ProgressAdminMessages.detail(ctx, "quest.status.none");
        }
    }

    private static void row(@Nonnull CommandContext ctx, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull String questId) {
        QuestStatus stored = engine.store().status(subject, questId);
        Quest quest = engine.quest(questId);
        if (quest == null) {
            ProgressAdminMessages.detail(ctx, "quest.status.orphan", questId,
                    ProgressAdminMessages.questStatus(stored));
            return;
        }
        QuestStatus effective = engine.status(subject, quest);
        List<Message> extras = new ArrayList<>();
        if (effective != stored) {
            extras.add(ProgressAdminMessages.piece("quest.status.stored",
                    ProgressAdminMessages.questStatus(stored)));
        }
        if (effective == QuestStatus.ON_COOLDOWN) {
            long wait = QuestLifecycle.offerableInMs(quest, subject, engine.store(), engine.now());
            extras.add(ProgressAdminMessages.piece("quest.status.back_in",
                    QuestLifecycle.formatCooldown(wait)));
        }
        ProgressAdminMessages.detail(ctx, "quest.status.row", questId,
                ProgressAdminMessages.questName(quest), ProgressAdminMessages.questStatus(effective),
                Msg.cat(extras.toArray(new Message[0])));
        if (stored == QuestStatus.ACTIVE || stored == QuestStatus.COMPLETED_UNCLAIMED) {
            steps(ctx, engine, subject, quest);
        }
    }

    private static void steps(@Nonnull CommandContext ctx, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull Quest quest) {
        Map<String, ObjectiveProgressState> progress = engine.progressOf(subject, quest.id());
        for (ObjectiveDef step : quest.objectives()) {
            ObjectiveProgressState state = progress.get(step.id());
            int current = state == null ? 0 : state.current();
            boolean done = state != null && state.isCompleted();
            Message line = ProgressionTexts.objective(quest.id(), step.id());
            ProgressAdminMessages.detail(ctx, done ? "quest.step.done" : "quest.step.open",
                    line != null ? line : Msg.raw(step.id()), current, step.amountAsInt());
        }
    }
}
