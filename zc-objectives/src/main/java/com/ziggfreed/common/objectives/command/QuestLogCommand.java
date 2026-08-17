package com.ziggfreed.common.objectives.command;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The three moves a player makes on their own quest log, which are one implementation:
 * {@code accept}, {@code claim} and {@code abandon}.
 *
 * <p>Three registered verbs rather than one with a mode argument - each is its own name, its own
 * help line and its own permission node, which is how the engine's own families read - built from
 * one class because each is a single engine call and the refusals around it. Unlike the admin verbs
 * beside them, these ASK the engine first: {@code accept} runs the eligibility check a player would
 * face, and {@code claim} refuses what is not parked for collection, so a script driving them gets
 * exactly what the player would.
 *
 * <p>Every mutating call goes through the registered {@link ProgressionCallScope}, so a claim made
 * from here fires exactly what the owning mod's own menu would have fired - its toast, its
 * follow-on grants, its bookkeeping. Without it a claim from a shared surface pays out in silence.
 *
 * <p>{@code claim --quest=all} collects everything parked that can be collected from NOWHERE in
 * particular. A quest that names a place it is collected at is skipped quietly rather than refused
 * one line at a time, because the engine would refuse a placeless claim of it by design and a loop
 * of refusals tells the reader nothing the single-quest form does not.
 */
final class QuestLogCommand extends TargetPlayerSubCommand {

    /** Which move this instance makes. */
    enum Move {
        ACCEPT(ProgressCommandLine.Quest.ACCEPT, "arg.quest"),
        CLAIM(ProgressCommandLine.Quest.CLAIM, "arg.quest_or_all"),
        ABANDON(ProgressCommandLine.Quest.ABANDON, "arg.quest");

        private final String verb;
        private final String argDescription;

        Move(@Nonnull String verb, @Nonnull String argDescription) {
            this.verb = verb;
            this.argDescription = argDescription;
        }
    }

    private final Move move;
    private final OptionalArg<String> questArg;

    QuestLogCommand(@Nonnull Move move) {
        super(ProgressCommandLine.Quest.GROUP, move.verb);
        this.move = move;
        this.questArg = withOptionalArg("quest", ProgressAdminMessages.desc(move.argDescription),
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
        if (move == Move.CLAIM && ProgressCommandLine.ALL.equalsIgnoreCase(questId)) {
            claimAll(ctx, engine, subject, target.name());
            return;
        }
        Quest quest = engine.quest(questId);
        if (quest == null) {
            ProgressAdminMessages.unknownQuest(ctx, questId);
            return;
        }
        switch (move) {
            case ACCEPT -> accept(ctx, engine, subject, quest, target.name());
            case CLAIM -> claim(ctx, engine, subject, quest, target.name());
            case ABANDON -> abandon(ctx, engine, subject, quest, target.name());
        }
    }

    private static void accept(@Nonnull CommandContext ctx, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull Quest quest, @Nonnull String name) {
        QuestEngine.AcceptCheck check = engine.canAccept(subject, quest);
        if (!check.allowed()) {
            // The reasons are the engine's own tokens - data, so a script can read them.
            ProgressAdminMessages.refused(ctx, "quest.accept.refused", quest.id(), name,
                    String.join(", ", check.reasons()));
            return;
        }
        boolean accepted = Boolean.TRUE.equals(scope().around(subject, s -> {
            boolean ok = engine.accept(s, quest);
            if (ok) {
                // A step already satisfied settles now rather than on the player's next move.
                engine.checkCompletion(s, quest);
            }
            return Boolean.valueOf(ok);
        }));
        if (accepted) {
            ProgressAdminMessages.done(ctx, "quest.accepted", quest.id(), name);
        } else {
            ProgressAdminMessages.refused(ctx, "quest.give.refused", quest.id(), name);
        }
    }

    private static void claim(@Nonnull CommandContext ctx, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull Quest quest, @Nonnull String name) {
        if (engine.store().status(subject, quest.id()) != QuestStatus.COMPLETED_UNCLAIMED) {
            ProgressAdminMessages.refused(ctx, "quest.claim.not_parked", quest.id(), name);
            return;
        }
        if (quest.turnInAt() != null) {
            ProgressAdminMessages.refused(ctx, "quest.claim.at_site", quest.id());
            return;
        }
        boolean paid = Boolean.TRUE.equals(scope().around(subject,
                s -> Boolean.valueOf(engine.claim(s, quest))));
        if (paid) {
            ProgressAdminMessages.done(ctx, "quest.claimed", quest.id(), name);
        } else {
            ProgressAdminMessages.refused(ctx, "quest.claim.refused", quest.id(), name);
        }
    }

    private static void claimAll(@Nonnull CommandContext ctx, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull String name) {
        int paid = 0;
        int refused = 0;
        for (Quest quest : List.copyOf(engine.activeAndUnclaimed(subject))) {
            if (engine.store().status(subject, quest.id()) != QuestStatus.COMPLETED_UNCLAIMED
                    || quest.turnInAt() != null) {
                continue;
            }
            if (Boolean.TRUE.equals(scope().around(subject, s -> Boolean.valueOf(engine.claim(s, quest))))) {
                paid++;
            } else {
                refused++;
            }
        }
        if (paid > 0) {
            ProgressAdminMessages.done(ctx, "quest.claimed.all", paid, name);
        }
        if (refused > 0) {
            ProgressAdminMessages.refused(ctx, "quest.claim.refused.some", refused, name);
        }
        if (paid == 0 && refused == 0) {
            ProgressAdminMessages.detail(ctx, "quest.claim.nothing", name);
        }
    }

    private static void abandon(@Nonnull CommandContext ctx, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull Quest quest, @Nonnull String name) {
        boolean dropped = Boolean.TRUE.equals(scope().around(subject,
                s -> Boolean.valueOf(engine.abandon(s, quest.id()))));
        if (dropped) {
            ProgressAdminMessages.done(ctx, "quest.abandoned", quest.id(), name);
        } else {
            ProgressAdminMessages.refused(ctx, "quest.abandon.not_active", quest.id(), name);
        }
    }

    @Nonnull
    private static ProgressionCallScope scope() {
        return ProgressionRuntime.questScope();
    }
}
