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
 * {@code AchievementMilestones}), re-reads the server owner's own quest folder from disk
 * ({@code mods/ziggfreedcommon/quests/<Id>.json}, which the fold reads afresh every time), and
 * republishes that layer at library-default rank. The layer a consumer mod converted from its own
 * format and handed into the quest store is folded again as it stands; only that consumer can
 * re-convert it, and its own reload command is where that lives. The counts reported afterwards are
 * the MERGED catalogue's, so they include what every consumer has published, not only what this
 * call refreshed.
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
