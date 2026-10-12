package com.ziggfreed.common.instance.dungeon.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;

/**
 * {@code list}: every loaded dungeon, four lines each: its name, whether it is open, its difficulty and
 * season; its template and prefab; how its copies are shared; and its entry, run and close knobs, each as
 * the effective value (a default where the files say nothing).
 */
final class DungeonListCommand extends AbstractAsyncCommand {

    DungeonListCommand() {
        super(ZigDungeonCommand.LIST, DungeonCommandMessages.desc(ZigDungeonCommand.LIST));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        DungeonConfig config = DungeonConfig.getInstance();
        List<String> ids = config.ids();
        if (ids.isEmpty()) {
            DungeonCommandMessages.detail(ctx, "list.none");
            return CompletableFuture.completedFuture(null);
        }
        DungeonCommandMessages.heading(ctx, "list.header", ids.size());
        for (String id : ids) {
            DungeonAsset dungeon = config.resolve(id);
            if (dungeon != null) {
                describe(ctx, id, dungeon);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void describe(@Nonnull CommandContext ctx, @Nonnull String id, @Nonnull DungeonAsset dungeon) {
        String season = dungeon.getSeason();
        DungeonCommandMessages.heading(ctx, "list.dungeon", DungeonCommandMessages.dungeonName(id, dungeon), id,
                DungeonCommandMessages.state(dungeon),
                dungeon.difficulty().isEmpty() ? DungeonCommandMessages.word("difficulty.normal")
                        : Msg.raw(dungeon.difficulty()),
                season == null ? DungeonCommandMessages.word("season.all_year") : Msg.raw(season));
        String prefab = dungeon.prefabPath();
        DungeonCommandMessages.detail(ctx, "list.place", dungeon.templateId(),
                prefab == null ? DungeonCommandMessages.word("prefab.none") : Msg.raw(prefab));
        DungeonCommandMessages.detail(ctx, "list.share", dungeon.areasPerCopy(), dungeon.spacing(),
                dungeon.maxPlayers(),
                DungeonCommandMessages.word(dungeon.partyLocked() ? "share.locked" : "share.open"));
        Vec3 entry = dungeon.entryOffset();
        Integer limit = dungeon.timeLimitSeconds();
        DungeonCommandMessages.detail(ctx, "list.run", entry.effectiveX(), entry.effectiveY(), entry.effectiveZ(),
                limit == null ? DungeonCommandMessages.word("run.no_limit")
                        : DungeonCommandMessages.word("run.limit", limit),
                DungeonCommandMessages.word(dungeon.failOnWipe() ? "wipe.fails" : "wipe.ignored"),
                dungeon.emptyCloseSeconds());
    }
}
