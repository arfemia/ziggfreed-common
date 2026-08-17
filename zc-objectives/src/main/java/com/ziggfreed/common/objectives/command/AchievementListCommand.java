package com.ziggfreed.common.objectives.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * {@code achievement list}: the MERGED achievement catalogue - every achievement any mod on this
 * server published - one row each, with the flags an administrator is usually scanning for: out of
 * circulation, hidden, a one-winner server-first, a capstone standing on other achievements.
 *
 * <p>{@code --tag} narrows the list to achievements carrying it, the one classification the runtime
 * object carries. Category and icon are presentation the folding layer keeps, so a consumer's own
 * grouping reads through its own listing.
 */
final class AchievementListCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> tagArg;

    AchievementListCommand() {
        super(ProgressCommandLine.Achievement.LIST,
                ProgressAdminMessages.desc(ProgressCommandLine.Achievement.GROUP + "."
                        + ProgressCommandLine.Achievement.LIST));
        this.tagArg = withOptionalArg("tag", ProgressAdminMessages.desc("arg.tag"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String tag = tagArg.provided(ctx) ? tagArg.get(ctx) : null;
        List<Achievement> listed = new ArrayList<>();
        for (Achievement achievement : ProgressionRuntime.achievements().achievements()) {
            if (tag == null || achievement.hasTag(tag)) {
                listed.add(achievement);
            }
        }
        listed.sort(Comparator.comparing(Achievement::id));
        ProgressAdminMessages.heading(ctx, "achievement.list.header", listed.size());
        if (listed.isEmpty()) {
            ProgressAdminMessages.detail(ctx, "achievement.list.none");
            return CompletableFuture.completedFuture(null);
        }
        for (Achievement achievement : listed) {
            row(ctx, achievement);
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void row(@Nonnull CommandContext ctx, @Nonnull Achievement achievement) {
        List<String> flags = new ArrayList<>();
        if (!achievement.available()) {
            flags.add("achievement.list.off");
        }
        if (achievement.hidden()) {
            flags.add("achievement.list.hidden");
        }
        if (achievement.serverFirst()) {
            flags.add("achievement.list.server_first");
        }
        if (achievement.isMeta()) {
            flags.add("achievement.list.meta");
        }
        ProgressAdminMessages.detail(ctx, "achievement.list.row", achievement.id(),
                ProgressAdminMessages.achievementName(achievement), achievement.criteria().size(),
                achievement.points(), ProgressAdminMessages.flags(flags));
    }
}
