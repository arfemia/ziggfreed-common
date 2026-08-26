package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code achievement give}: earn an achievement for a player by hand, whether or not its criteria
 * are met - the same verb, and the same meaning, as {@code quest give}: put this content into the
 * player's hands regardless of its gates.
 *
 * <p>It is the engine's own {@link AchievementEngine#unlock}, under the registered scope, so an earn
 * from here fires the owning mod's toast and follow-on grants exactly as a real earn would. It
 * still asks the consumer's earn gate, which is where a server-first somebody else already holds is
 * refused; the verb says so instead of reporting an earn that did not happen.
 */
final class AchievementGiveCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> achievementArg;

    AchievementGiveCommand() {
        super(ProgressCommandLine.Achievement.GROUP, ProgressCommandLine.Achievement.GIVE);
        this.achievementArg = withOptionalArg("achievement",
                ProgressAdminMessages.desc("arg.achievement"), ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Achievement achievement = ContentArgs.achievement(ctx, achievementArg);
        if (achievement == null) {
            return;
        }
        Subject subject = achievementSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        AchievementEngine engine = ProgressionRuntime.achievements();
        if (engine.isUnlocked(subject, achievement.id())) {
            ProgressAdminMessages.refused(ctx, "achievement.give.already", achievement.id(),
                    target.name());
            return;
        }
        boolean earned = Boolean.TRUE.equals(ProgressionRuntime.achievementScope().around(subject,
                s -> Boolean.valueOf(engine.unlock(s, achievement))));
        if (earned) {
            ProgressAdminMessages.done(ctx, "achievement.given", achievement.id(), target.name());
        } else {
            ProgressAdminMessages.refused(ctx, "achievement.give.refused", achievement.id(),
                    target.name());
        }
    }
}
