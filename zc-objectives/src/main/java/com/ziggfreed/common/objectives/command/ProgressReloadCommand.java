package com.ziggfreed.common.objectives.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * {@code reload}: fold the shared quest and achievement assets again, publish them into the
 * runtime, and re-run their audit - exactly what happens once at boot, on demand.
 *
 * <p><b>What it can reload, and what it cannot.</b> It re-reads whatever the engine's asset stores
 * hold right now for the SHARED schema ({@code Server/ZiggfreedCommon/Quests}, {@code Achievements},
 * {@code AchievementMilestones}) and republishes that layer at library-default rank. Content a
 * consumer mod folds from its own format, and a consumer's owner overrides, are that consumer's
 * layer, published under its own name; only it can re-read them, and its own reload command is where
 * that lives. The counts reported afterwards are the MERGED catalogue's, so they include what every
 * consumer has published, not only what this call refreshed.
 */
final class ProgressReloadCommand extends AbstractAsyncCommand {

    ProgressReloadCommand() {
        super(ProgressCommandLine.RELOAD, ProgressAdminMessages.desc(ProgressCommandLine.RELOAD));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        ProgressionDefaults.publishAssetContent();
        ProgressAdminMessages.done(ctx, "reload.done",
                ProgressionRuntime.quests().quests().size(),
                ProgressionRuntime.achievements().achievements().size());
        return CompletableFuture.completedFuture(null);
    }
}
