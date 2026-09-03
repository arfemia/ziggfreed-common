package com.ziggfreed.common.encounter.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.asset.EncounterOwnerLayers;
import com.ziggfreed.common.encounter.asset.EncounterParticipationConfig;
import com.ziggfreed.common.encounter.validate.EncounterScripts;

/**
 * Re-read the owner files ({@code encounters.json}, {@code encounter-participation.json}) and drop
 * every cached script reading. It can only re-read the layers this library owns; a pack's own files
 * are the engine's asset reload.
 */
final class EncounterReloadCommand extends AbstractAsyncCommand {

    EncounterReloadCommand() {
        super(EncounterCommandLine.RELOAD, EncounterAdminMessages.desc(EncounterCommandLine.RELOAD));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        EncounterOwnerLayers.reloadAll();
        EncounterScripts.invalidate();
        EncounterAdminMessages.done(ctx, "reload.done", EncounterBindingConfig.getInstance().all().size(),
                EncounterParticipationConfig.getInstance().all().size(), EncounterScripts.scanLoaded().size());
        return CompletableFuture.completedFuture(null);
    }
}
