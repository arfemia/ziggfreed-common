package com.ziggfreed.common.instance.dungeon.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.layout.Layout;
import com.ziggfreed.common.instance.dungeon.layout.LayoutGenerator;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.LayoutResult;
import com.ziggfreed.common.instance.dungeon.layout.PlacedRoom;
import com.ziggfreed.common.instance.dungeon.layout.QuarterTurns;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.asset.DungeonRoomConfig;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;

/**
 * {@code layout <dungeon> <seed>}: the rooms a generated dungeon's layout plan makes from one seed, as a run with that
 * seed would get them, without opening anything: a heading (the plan, the seed, how many rooms and how many on the
 * path, how far the plan was relaxed, how long it took), then one line per room in the layout's order (its id, the
 * variant it pastes, where its anchor stands in the layout's frame, its turn, the role it fills and what it is to
 * the layout). The catalogue is read against the packs loaded now.
 */
final class DungeonLayoutCommand extends AbstractAsyncCommand {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final RequiredArg<String> dungeonArg;
    private final RequiredArg<String> seedArg;

    DungeonLayoutCommand() {
        super(ZigDungeonCommand.LAYOUT, DungeonCommandMessages.desc(ZigDungeonCommand.LAYOUT));
        this.dungeonArg = withRequiredArg(ZigDungeonCommand.ARG_DUNGEON, DungeonCommandMessages.desc("arg.dungeon"),
                ArgTypes.STRING);
        this.seedArg = withRequiredArg(ZigDungeonCommand.ARG_SEED, DungeonCommandMessages.desc("arg.seed"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String id = dungeonArg.get(ctx).trim();
        String seedText = seedArg.get(ctx).trim();
        DungeonAsset dungeon = id.isEmpty() ? null : DungeonConfig.getInstance().resolve(id.toLowerCase(Locale.ROOT));
        if (dungeon == null) {
            DungeonCommandMessages.refused(ctx, "enter.unknown", id);
            return CompletableFuture.completedFuture(null);
        }
        String planId = dungeon.layoutId();
        if (planId == null) {
            DungeonCommandMessages.refused(ctx, "layout.not_generated", DungeonCommandMessages.dungeonName(dungeon));
            return CompletableFuture.completedFuture(null);
        }
        LayoutPlan plan = dungeon.layoutPlan();
        if (plan == null) {
            DungeonCommandMessages.refused(ctx, "enter.missing_layout", DungeonCommandMessages.dungeonName(dungeon),
                    Msg.raw(planId));
            return CompletableFuture.completedFuture(null);
        }
        Long seed = parseSeed(seedText);
        if (seed == null) {
            DungeonCommandMessages.refused(ctx, "layout.bad_seed", seedText);
            return CompletableFuture.completedFuture(null);
        }
        RoomCatalogue catalogue = DungeonRoomConfig.getInstance().catalogue(PrefabPoolIndex.live());
        long start = System.nanoTime();
        LayoutResult result = LayoutGenerator.generate(plan, catalogue, seed);
        double ms = (System.nanoTime() - start) / NANOS_PER_MILLI;
        Layout layout = result.layout();
        if (layout == null) {
            DungeonCommandMessages.refused(ctx, "layout.none", Msg.raw(plan.id()), Msg.raw(seedText),
                    result.tries(), Msg.raw(result.failure() == null ? "" : result.failure()));
            return CompletableFuture.completedFuture(null);
        }
        DungeonCommandMessages.heading(ctx, "layout.header", Msg.raw(plan.id()), Msg.raw(seedText),
                layout.rooms().size(), layout.pathLength(), relaxed(result.relax()), Math.round(ms * 100.0) / 100.0);
        for (PlacedRoom room : layout.rooms()) {
            DungeonCommandMessages.detail(ctx, "layout.room", room.index(), Msg.raw(room.roomId()), room.variant(),
                    room.origin().x(), room.origin().y(), room.origin().z(),
                    QuarterTurns.normalize(room.rotation()) * 90, Msg.raw(room.role()),
                    DungeonCommandMessages.word("layout.part." + room.part().name().toLowerCase(Locale.ROOT)));
        }
        return CompletableFuture.completedFuture(null);
    }

    /** A seed as typed: a whole number, the way a run's own seed is logged; null for anything else. */
    @Nullable
    static Long parseSeed(@Nonnull String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** How far the plan was relaxed to fit, as a keyed word. */
    @Nonnull
    private static Message relaxed(@Nonnull LayoutResult.Relax relax) {
        return DungeonCommandMessages.word("layout.relax." + relax.name().toLowerCase(Locale.ROOT));
    }
}
