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
 * The two ways an administrator moves ONE achievement by hand, which are one implementation:
 * {@code unlock} earns it whether or not its criteria are met, {@code revoke} takes it back.
 *
 * <p>Two registered verbs from one class, for the same reason the quest-log verbs are: each names
 * exactly one thing, and naming two would need a mode argument nobody could guess from the help.
 *
 * <p>Both are the engine's own calls, under the registered scope, so an earn from here fires the
 * owning mod's toast and follow-on grants exactly as a real earn would. {@code unlock} still asks the
 * consumer's earn gate, which is where a server-first somebody else already holds is refused; the
 * verb says so instead of reporting an earn that did not happen. {@code revoke} takes a capstone
 * standing on the revoked achievement back with it, because that is what the engine's revoke means.
 */
final class AchievementMarkCommand extends TargetPlayerSubCommand {

    /** What this instance does to an achievement. */
    enum Op {
        UNLOCK(ProgressCommandLine.Achievement.UNLOCK),
        REVOKE(ProgressCommandLine.Achievement.REVOKE);

        private final String verb;

        Op(@Nonnull String verb) {
            this.verb = verb;
        }
    }

    private final Op op;
    private final OptionalArg<String> achievementArg;

    AchievementMarkCommand(@Nonnull Op op) {
        super(ProgressCommandLine.Achievement.GROUP, op.verb);
        this.op = op;
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
        if (op == Op.UNLOCK) {
            unlock(ctx, engine, subject, achievement, target.name());
        } else {
            revoke(ctx, engine, subject, achievement, target.name());
        }
    }

    private static void unlock(@Nonnull CommandContext ctx, @Nonnull AchievementEngine engine,
            @Nonnull Subject subject, @Nonnull Achievement achievement, @Nonnull String name) {
        if (engine.isUnlocked(subject, achievement.id())) {
            ProgressAdminMessages.refused(ctx, "achievement.unlock.already", achievement.id(), name);
            return;
        }
        boolean earned = Boolean.TRUE.equals(ProgressionRuntime.achievementScope().around(subject,
                s -> Boolean.valueOf(engine.unlock(s, achievement))));
        if (earned) {
            ProgressAdminMessages.done(ctx, "achievement.unlocked", achievement.id(), name);
        } else {
            ProgressAdminMessages.refused(ctx, "achievement.unlock.refused", achievement.id(), name);
        }
    }

    private static void revoke(@Nonnull CommandContext ctx, @Nonnull AchievementEngine engine,
            @Nonnull Subject subject, @Nonnull Achievement achievement, @Nonnull String name) {
        boolean taken = Boolean.TRUE.equals(ProgressionRuntime.achievementScope().around(subject,
                s -> Boolean.valueOf(engine.revoke(s, achievement.id()))));
        if (taken) {
            ProgressAdminMessages.done(ctx, "achievement.revoked", achievement.id(), name);
        } else {
            ProgressAdminMessages.refused(ctx, "achievement.revoke.nothing", achievement.id(), name);
        }
    }
}
