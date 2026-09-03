package com.ziggfreed.common.encounter.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterRuntime;

/** Force a live encounter's script into a state, through the engine's own public state setter. */
final class EncounterStateCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> refArg;
    private final RequiredArg<String> stateArg;
    private final OptionalArg<String> subStateArg;

    EncounterStateCommand() {
        super(EncounterCommandLine.STATE, EncounterAdminMessages.desc(EncounterCommandLine.STATE));
        this.refArg = withRequiredArg(EncounterCommandLine.ARG_REF, EncounterAdminMessages.desc("arg.ref"),
                ArgTypes.STRING);
        this.stateArg = withRequiredArg(EncounterCommandLine.ARG_STATE, EncounterAdminMessages.desc("arg.state"),
                ArgTypes.STRING);
        this.subStateArg = withOptionalArg(EncounterCommandLine.ARG_SUBSTATE,
                EncounterAdminMessages.desc("arg.substate"), ArgTypes.STRING);
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
        String state = stateArg.get(ctx);
        String subState = subStateArg.provided(ctx) ? subStateArg.get(ctx) : null;
        return runAsync(ctx, () -> set(ctx, world, live, state, subState), world);
    }

    private static void set(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull EncounterRuns.Live live,
            @Nonnull String state, @Nullable String subState) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (EncounterRuntime.setState(store, live.encounterRef(), state, subState)) {
            String now = EncounterRuntime.state(store, live.encounterRef());
            EncounterAdminMessages.done(ctx, "state.done", live.encounterId(), now == null ? state : now);
        } else {
            EncounterAdminMessages.refused(ctx, "state.refused", live.encounterId());
        }
    }
}
