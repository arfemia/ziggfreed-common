package com.ziggfreed.common.encounter.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterRuntime;

/**
 * Every live encounter: its script, its run, its world, the script's own state, its members and
 * its phase. Each row is read on its own world's thread, one world after another.
 */
final class EncounterListCommand extends AbstractAsyncCommand {

    EncounterListCommand() {
        super(EncounterCommandLine.LIST, EncounterAdminMessages.desc(EncounterCommandLine.LIST));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        List<EncounterRuns.Live> live = EncounterRuns.allLive();
        if (live.isEmpty()) {
            EncounterAdminMessages.detail(ctx, "list.none");
            return CompletableFuture.completedFuture(null);
        }
        EncounterAdminMessages.heading(ctx, "list.header", live.size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (EncounterRuns.Live entry : live) {
            World world = Universe.get().getWorld(entry.worldUuid());
            if (world == null) {
                continue;
            }
            chain = chain.thenCompose(v -> runAsync(ctx, () -> row(ctx, world, entry), world));
        }
        return chain;
    }

    private static void row(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull EncounterRuns.Live live) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        String state = EncounterRuntime.state(store, live.encounterRef());
        EncounterRun run = EncounterRun.of(live.run(), live.encounterId());
        int members = EncounterRuns.memberIds(store, live.encounterRef()).size();
        if (run.isEngaged()) {
            EncounterAdminMessages.detail(ctx, "list.row", live.encounterId(), run.shortId(), world.getName(),
                    state == null ? "?" : state, members, run.phase() == null ? "-" : run.phase());
        } else {
            EncounterAdminMessages.detail(ctx, "list.row.idle", live.encounterId(), run.shortId(), world.getName(),
                    state == null ? "?" : state);
        }
    }
}
