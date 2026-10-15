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
import com.ziggfreed.common.instance.leaderboard.LeaderboardSplit;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardBossFights;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardRecords;

/**
 * Open Records' Bosses tab for the caller: with {@code --encounter} (its script id, or its binding row's id) that
 * boss picked under the choice that offers it, else All bosses. The caller's own native readings are brought up to
 * date first, as Records' own tab does.
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
        String named = encounterArg.provided(ctx) ? encounterArg.get(ctx) : null;
        String id = named == null || named.isBlank() ? null : named.trim();
        EncounterBindingAsset row = id == null ? null : rowNamed(id);
        if (id != null && row == null) {
            LeaderboardCommandMessages.refused(ctx, "encounter.unranked", id);
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> open(ctx, store, ref, row, id), world);
    }

    /** The binding row {@code id} names, as a script id or a row id, or null when none does. */
    @Nullable
    private static EncounterBindingAsset rowNamed(@Nonnull String id) {
        EncounterBindingConfig rows = EncounterBindingConfig.getInstance();
        EncounterBindingAsset row = rows.forEncounter(id);
        return row != null ? row : rows.resolve(id);
    }

    /**
     * On the world thread: bring the caller's readings up to date, then open Records on the Bosses tab, {@code row}'s
     * boss picked under the choice that offers it (null for All bosses), or say why it could not.
     */
    private static void open(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nullable EncounterBindingAsset row, @Nullable String named) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef viewer = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || viewer == null) {
            LeaderboardCommandMessages.refused(ctx, "open.failed");
            return;
        }
        StatBoardRecords.INSTANCE.opening(store, ref);
        LeaderboardPageDeps deps = StatBoardRecords.INSTANCE.deps();
        LeaderboardSplit bosses = deps == null ? null : deps.split(StatBoardBossFights.SECTION);
        if (bosses == null) {
            LeaderboardCommandMessages.refused(ctx, "encounter.none");
            return;
        }
        String pick = row == null ? null : StatBoardBossFights.pickOf(row);
        String choice = pick == null ? "" : bosses.choiceOf(pick);
        if (choice == null) {
            LeaderboardCommandMessages.refused(ctx, "encounter.unranked", named);
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new LeaderboardPage(viewer, deps,
                StatBoardBossFights.SECTION, null, null, null, true, choice, pick));
    }
}
