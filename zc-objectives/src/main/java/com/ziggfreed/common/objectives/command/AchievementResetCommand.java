package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code achievement reset}: an administrator starting a player over on one achievement, or on all
 * of them - the same one-or-all scope, spelled the same way, as {@code quest reset}.
 *
 * <p>Resetting ONE achievement is the engine's {@link AchievementEngine#revoke}: its earn, its
 * partial tallies, its pin all go, and a capstone standing only on it is taken back too, because a
 * capstone left earned over a wiped child would be a record the criteria no longer support.
 * Resetting ALL is {@link AchievementEngine#resetAll}: every earn, every partial tally, every pin,
 * every milestone, so a session can re-run the same achievements end to end.
 *
 * <p>What neither reaches is a server-first this player WON: the claim table is a seam that records
 * the winner and offers no release, so a consumer that installed a durable table releases what it
 * recorded through its own command, and the all form says so once when there was such a win to
 * mention. On the library's own boot-lifetime table nothing needs saying: it forgets everything at
 * restart anyway.
 */
final class AchievementResetCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> achievementArg;

    AchievementResetCommand() {
        super(ProgressCommandLine.Achievement.GROUP, ProgressCommandLine.Achievement.RESET);
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
            resetAll(ctx, engine, subject, target.name());
            return;
        }
        // A record whose definition has gone (an orphan) is exactly what an administrator may be
        // here to clear, so an id the catalogue no longer knows is still wiped when the player
        // carries a record under it; only an id NEITHER side knows is a typo.
        Achievement achievement = engine.achievement(achievementId);
        if (achievement == null
                && !engine.store().knownAchievementIds(subject).contains(achievementId)) {
            ProgressAdminMessages.unknownAchievement(ctx, achievementId);
            return;
        }
        boolean wiped = Boolean.TRUE.equals(ProgressionRuntime.achievementScope().around(subject,
                s -> Boolean.valueOf(engine.revoke(s, achievementId))));
        if (wiped) {
            ProgressAdminMessages.done(ctx, "achievement.reset.one", achievementId, target.name());
        } else {
            ProgressAdminMessages.refused(ctx, "achievement.reset.nothing", achievementId,
                    target.name());
        }
    }

    private static void resetAll(@Nonnull CommandContext ctx, @Nonnull AchievementEngine engine,
            @Nonnull Subject subject, @Nonnull String name) {
        boolean heldAFirst = holdsAServerFirst(engine, subject);
        int wiped = ProgressionRuntime.achievementScope().around(subject, engine::resetAll);
        ProgressAdminMessages.done(ctx, "achievement.reset.all", name, wiped);
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
