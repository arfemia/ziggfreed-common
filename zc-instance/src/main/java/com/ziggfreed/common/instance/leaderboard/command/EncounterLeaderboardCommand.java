package com.ziggfreed.common.instance.leaderboard.command;

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
import com.ziggfreed.common.instance.leaderboard.LeaderboardPage;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPageDeps;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardBossFights;

/**
 * Open Records' Boss Fights for the caller, a fight per tab: at the fight named with {@code --encounter}
 * (its script id, or its binding row's id), or at the first fight on record when none is named.
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
        if (StatBoard.board() == null || !StatBoard.source().enabled()) {
            LeaderboardCommandMessages.refused(ctx, "encounter.off");
            return CompletableFuture.completedFuture(null);
        }
        LeaderboardPageDeps deps = StatBoardBossFights.deps();
        String named = encounterArg.provided(ctx) ? encounterArg.get(ctx) : null;
        String tab = null;
        if (named != null && !named.isBlank()) {
            String id = named.trim();
            EncounterBindingAsset row = rowNamed(id);
            tab = row == null || deps == null ? null : StatBoardBossFights.tabOf(deps, row);
            if (tab == null) {
                LeaderboardCommandMessages.refused(ctx, "encounter.unranked", id);
                return CompletableFuture.completedFuture(null);
            }
        }
        if (deps == null) {
            LeaderboardCommandMessages.refused(ctx, "encounter.none");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        String selected = tab;
        return runAsync(ctx, () -> open(ctx, store, ref, deps, selected), world);
    }

    /** The binding row {@code id} names, as a script id or a row id, or null when none does. */
    @Nullable
    private static EncounterBindingAsset rowNamed(@Nonnull String id) {
        EncounterBindingConfig rows = EncounterBindingConfig.getInstance();
        EncounterBindingAsset row = rows.forEncounter(id);
        return row != null ? row : rows.resolve(id);
    }

    /** On the world thread: open the page at {@code tab} (null for the first fight), or say it could not. */
    private static void open(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull LeaderboardPageDeps deps, @Nullable String tab) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef viewer = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || viewer == null) {
            LeaderboardCommandMessages.refused(ctx, "open.failed");
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new LeaderboardPage(viewer, deps, tab, null, null, null, true));
    }
}
