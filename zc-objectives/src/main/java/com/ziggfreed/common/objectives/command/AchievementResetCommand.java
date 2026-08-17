package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code achievement reset}: wipe a player's whole achievement record - every earn, every partial
 * tally, every pin, every milestone - so a session can re-run the same achievements end to end.
 *
 * <p>It is the engine's {@link AchievementEngine#resetAll}, under the registered scope. What it does
 * NOT reach is a server-first this player WON: the claim table is a seam that records the winner and
 * offers no release, so a consumer that installed a durable table releases what it recorded through
 * its own command, and this verb says so once when there was such a win to mention. On the library's
 * own boot-lifetime table nothing needs saying: it forgets everything at restart anyway.
 */
final class AchievementResetCommand extends TargetPlayerSubCommand {

    AchievementResetCommand() {
        super(ProgressCommandLine.Achievement.GROUP, ProgressCommandLine.Achievement.RESET);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Subject subject = achievementSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        AchievementEngine engine = ProgressionRuntime.achievements();
        boolean heldAFirst = holdsAServerFirst(engine, subject);
        int wiped = ProgressionRuntime.achievementScope().around(subject, engine::resetAll);
        ProgressAdminMessages.done(ctx, "achievement.reset", target.name(), wiped);
        if (heldAFirst && !FirstClaims.isDefault()) {
            ProgressAdminMessages.detail(ctx, "achievement.reset.first_claims");
        }
    }

    /** Had this player earned an achievement only one player may ever earn? Read BEFORE the wipe. */
    private static boolean holdsAServerFirst(@Nonnull AchievementEngine engine,
            @Nonnull Subject subject) {
        for (Achievement achievement : engine.achievements()) {
            if (achievement.serverFirst() && engine.isUnlocked(subject, achievement.id())) {
                return true;
            }
        }
        return false;
    }
}
