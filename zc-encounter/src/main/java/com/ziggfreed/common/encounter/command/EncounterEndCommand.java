package com.ziggfreed.common.encounter.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterSpawner;

/** Remove a live encounter; an engaged run is settled as a wipe first. */
final class EncounterEndCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> refArg;

    EncounterEndCommand() {
        super(EncounterCommandLine.END, EncounterAdminMessages.desc(EncounterCommandLine.END));
        this.refArg = withRequiredArg(EncounterCommandLine.ARG_REF, EncounterAdminMessages.desc("arg.ref"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        EncounterRuns.Live live = EncounterRefs.resolve(ctx, refArg.get(ctx));
        if (live == null) {
            return CompletableFuture.completedFuture(null);
        }
        World world = EncounterRefs.worldOf(ctx, live);
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        return runAsync(ctx, () -> end(ctx, world, live), world);
    }

    private static void end(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull EncounterRuns.Live live) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        String shortId = EncounterRun.shortId(live.run().runId());
        if (EncounterSpawner.despawn(store, live.encounterRef(), "ended by command")) {
            EncounterAdminMessages.done(ctx, "end.done", live.encounterId(), shortId);
        } else {
            EncounterAdminMessages.refused(ctx, "ref.gone");
        }
    }
}
