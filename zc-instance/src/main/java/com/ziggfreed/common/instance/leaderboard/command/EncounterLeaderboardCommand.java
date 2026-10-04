package com.ziggfreed.common.instance.leaderboard.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.instance.leaderboard.EncounterBoards;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardMessages;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPage;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPageDeps;

/**
 * Open one boss fight's records for the caller: the fight named with {@code --encounter} (its script
 * id, or its binding row's id), or the only fight on record when none is named. With several on record
 * and none named, it lists them, each with the line that opens it.
 */
final class EncounterLeaderboardCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> encounterArg;

    EncounterLeaderboardCommand() {
        super(LeaderboardCommandLine.ENCOUNTER, LeaderboardCommandMessages.desc(LeaderboardCommandLine.ENCOUNTER));
        this.encounterArg = withOptionalArg(LeaderboardCommandLine.ARG_ENCOUNTER,
                LeaderboardCommandMessages.desc("arg.encounter"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        // A screen is the whole point of the verb, and only a player has one.
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            LeaderboardCommandMessages.refused(ctx, "open.needs_player");
            return CompletableFuture.completedFuture(null);
        }
        Leaderboard board = EncounterLeaderboardListener.board();
        if (board == null) {
            LeaderboardCommandMessages.refused(ctx, "open.failed");
            return CompletableFuture.completedFuture(null);
        }
        EncounterBindingAsset row = pick(ctx, board, encounterArg.provided(ctx) ? encounterArg.get(ctx) : null);
        EncounterBindingAsset.Leaderboard group = row == null ? null : row.getLeaderboard();
        if (group == null) {
            return CompletableFuture.completedFuture(null);
        }
        LeaderboardPageDeps deps = EncounterBoards.deps(board, group, EncounterBoards.layoutFor(group),
                new EncounterLeaderboardMessages(row.getNameKey()));
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> open(ctx, store, ref, deps), world);
    }

    /** The row to open, or null having said why not (or having listed the choices). */
    @Nullable
    private static EncounterBindingAsset pick(@Nonnull CommandContext ctx, @Nonnull Leaderboard board,
            @Nullable String named) {
        EncounterBindingConfig rows = EncounterBindingConfig.getInstance();
        if (named != null && !named.isBlank()) {
            String id = named.trim();
            EncounterBindingAsset row = rows.forEncounter(id);
            if (row == null) {
                row = rows.resolve(id);
            }
            if (row == null || row.getLeaderboard() == null || row.getLeaderboard().getBucket() == null) {
                LeaderboardCommandMessages.refused(ctx, "encounter.unranked", id);
                return null;
            }
            return row;
        }
        List<EncounterBindingAsset> ranked = EncounterBoards.ranked(board, rows.all().values());
        if (ranked.isEmpty()) {
            LeaderboardCommandMessages.refused(ctx, "encounter.none");
            return null;
        }
        if (ranked.size() == 1) {
            return ranked.get(0);
        }
        LeaderboardCommandMessages.heading(ctx, "encounter.choose");
        for (EncounterBindingAsset row : ranked) {
            LeaderboardCommandMessages.detail(ctx, "encounter.choice",
                    EncounterLifecycle.titleOf(row.encounterAsset(), row), row.encounterAsset());
        }
        return null;
    }

    /** On the world thread: open the page, or say it could not. */
    private static void open(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull LeaderboardPageDeps deps) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef viewer = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || viewer == null) {
            LeaderboardCommandMessages.refused(ctx, "open.failed");
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new LeaderboardPage(viewer, deps));
    }
}
