package com.ziggfreed.common.objectives.command;

import java.util.TreeSet;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code achievement claim}: collect an earned achievement's waiting rewards - the peer of
 * {@code quest claim}, so the two groups conjugate the same way.
 *
 * <p>Like the quest twin it ASKS the engine first: only an achievement standing at
 * {@link AchievementStatus#UNLOCKED} (earned, rewards waiting) can be collected, and the refusal
 * says which side of that the achievement is on. Every collect goes through the registered scope,
 * so a claim made from here fires exactly what the owning mod's own menu would have fired.
 *
 * <p>{@code claim --achievement=all} collects everything earned and still waiting. An achievement
 * whose rewards will not fit is refused by the engine one at a time and counted, because unlike a
 * site-bound quest there is no by-design reason a waiting achievement cannot be collected from
 * here.
 */
final class AchievementClaimCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> achievementArg;

    AchievementClaimCommand() {
        super(ProgressCommandLine.Achievement.GROUP, ProgressCommandLine.Achievement.CLAIM);
        this.achievementArg = withOptionalArg("achievement",
                ProgressAdminMessages.desc("arg.achievement_or_all"), ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        String achievementId = ContentArgs.value(ctx, achievementArg);
        if (achievementId == null) {
            ProgressAdminMessages.refused(ctx, "achievement.needed");
            return;
        }
        Subject subject = achievementSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        AchievementEngine engine = ProgressionRuntime.achievements();
        if (ProgressCommandLine.ALL.equalsIgnoreCase(achievementId)) {
            claimAll(ctx, engine, subject, target.name());
            return;
        }
        Achievement achievement = engine.achievement(achievementId);
        if (achievement == null) {
            ProgressAdminMessages.unknownAchievement(ctx, achievementId);
            return;
        }
        if (engine.status(subject, achievement.id()) != AchievementStatus.UNLOCKED) {
            ProgressAdminMessages.refused(ctx, "achievement.claim.not_waiting", achievement.id(),
                    target.name());
            return;
        }
        boolean paid = Boolean.TRUE.equals(ProgressionRuntime.achievementScope().around(subject,
                s -> Boolean.valueOf(engine.claim(s, achievement))));
        if (paid) {
            ProgressAdminMessages.done(ctx, "achievement.claimed", achievement.id(), target.name());
        } else {
            ProgressAdminMessages.refused(ctx, "achievement.claim.refused", achievement.id(),
                    target.name());
        }
    }

    private static void claimAll(@Nonnull CommandContext ctx, @Nonnull AchievementEngine engine,
            @Nonnull Subject subject, @Nonnull String name) {
        int paid = 0;
        int refused = 0;
        for (String achievementId : new TreeSet<>(engine.store().knownAchievementIds(subject))) {
            Achievement achievement = engine.achievement(achievementId);
            if (achievement == null
                    || engine.status(subject, achievementId) != AchievementStatus.UNLOCKED) {
                continue;
            }
            if (Boolean.TRUE.equals(ProgressionRuntime.achievementScope().around(subject,
                    s -> Boolean.valueOf(engine.claim(s, achievement))))) {
                paid++;
            } else {
                refused++;
            }
        }
        if (paid > 0) {
            ProgressAdminMessages.done(ctx, "achievement.claimed.all", paid, name);
        }
        if (refused > 0) {
            ProgressAdminMessages.refused(ctx, "achievement.claim.refused.some", refused, name);
        }
        if (paid == 0 && refused == 0) {
            ProgressAdminMessages.detail(ctx, "achievement.claim.nothing", name);
        }
    }
}
