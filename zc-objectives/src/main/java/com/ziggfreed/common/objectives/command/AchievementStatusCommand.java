package com.ziggfreed.common.objectives.command;

import java.util.TreeSet;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code achievement status}: where every achievement the store has a record of stands for one
 * player - status and criteria tally per row - then the points total and every milestone reached.
 *
 * <p>Only achievements the player has SOME record of are listed, because the catalogue can run to
 * hundreds and a wall of "locked, 0/1" tells the reader nothing about this player; an optional
 * {@code --achievement} filter narrows the listing to one, exactly as the quest twin's
 * {@code --quest} does. An id whose definition has gone is shown as an orphan rather than skipped,
 * on the same reasoning as the quest twin: a record nothing can progress is what somebody came
 * here to find.
 */
final class AchievementStatusCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> achievementArg;

    AchievementStatusCommand() {
        super(ProgressCommandLine.Achievement.GROUP, ProgressCommandLine.Achievement.STATUS);
        this.achievementArg = withOptionalArg("achievement",
                ProgressAdminMessages.desc("arg.achievement_filter"), ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Subject subject = achievementSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        String only = ContentArgs.value(ctx, achievementArg);
        AchievementEngine engine = ProgressionRuntime.achievements();
        ProgressAdminMessages.heading(ctx, "achievement.status.header", target.name(),
                engine.points(subject));
        int shown = 0;
        for (String achievementId : new TreeSet<>(engine.store().knownAchievementIds(subject))) {
            if (only != null && !only.equalsIgnoreCase(achievementId)) {
                continue;
            }
            row(ctx, engine, subject, achievementId);
            shown++;
        }
        if (shown == 0) {
            ProgressAdminMessages.detail(ctx, "achievement.status.none");
        }
        if (only != null) {
            return;
        }
        for (Integer threshold : new TreeSet<>(engine.store().knownMilestones(subject))) {
            ProgressAdminMessages.detail(ctx, "achievement.status.milestone", threshold,
                    ProgressAdminMessages.achievementStatus(
                            engine.milestoneStatus(subject, threshold.intValue())));
        }
    }

    private static void row(@Nonnull CommandContext ctx, @Nonnull AchievementEngine engine,
            @Nonnull Subject subject, @Nonnull String achievementId) {
        Achievement achievement = engine.achievement(achievementId);
        if (achievement == null) {
            ProgressAdminMessages.detail(ctx, "achievement.status.orphan", achievementId,
                    ProgressAdminMessages.achievementStatus(engine.status(subject, achievementId)));
            return;
        }
        AchievementEngine.CriterionTally tally = engine.tally(subject, achievement);
        ProgressAdminMessages.detail(ctx, "achievement.status.row", achievementId,
                ProgressAdminMessages.achievementName(achievement),
                ProgressAdminMessages.achievementStatus(engine.status(subject, achievementId)),
                tally.completed(), tally.total());
    }
}
