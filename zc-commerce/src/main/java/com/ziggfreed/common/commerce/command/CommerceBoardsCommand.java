package com.ziggfreed.common.commerce.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.commerce.fold.AssetBoardCatalog;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;

/**
 * The boards this server has, what may be posted on each, and where each is in its rotation.
 *
 * <p>Naming one with {@code --board} also lists the contracts eligible for it, which is the answer
 * to the question a board almost always raises: why is this one showing and that one not.
 *
 * <p>The eligible set is what the DRAW picks from; which of them is on show is the draw's own
 * business, and the same pure function of the board id, the period and the seed for every player.
 */
final class CommerceBoardsCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> boardArg;

    CommerceBoardsCommand() {
        super(CommerceCommandLine.BOARDS, CommerceAdminMessages.desc(CommerceCommandLine.BOARDS));
        this.boardArg = withOptionalArg("board", CommerceAdminMessages.desc("arg.board"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        long nowMs = System.currentTimeMillis();
        String wanted = boardArg.provided(ctx) ? boardArg.get(ctx) : null;
        List<BoardAssetSpec> boards = AssetBoardCatalog.getInstance().boards();
        CommerceAdminMessages.heading(ctx, "boards.header", boards.size());
        if (boards.isEmpty()) {
            CommerceAdminMessages.detail(ctx, "boards.none");
            return CompletableFuture.completedFuture(null);
        }
        for (BoardAssetSpec board : boards) {
            if (wanted == null || wanted.equalsIgnoreCase(board.boardId())) {
                board(ctx, board, nowMs, wanted != null);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private static void board(@Nonnull CommandContext ctx, @Nonnull BoardAssetSpec board, long nowMs,
            boolean withContracts) {
        List<BountyRef> members = membersOf(board.boardId());
        RotationSpec rotation = board.rotation();
        CommerceAdminMessages.heading(ctx, "boards.row", board.boardId(),
                rotation.periodIndex(nowMs), minutes(rotation, nowMs), board.slots().size(),
                members.size());
        for (PoolSlot slot : board.slots()) {
            CommerceAdminMessages.detail(ctx, "boards.slot", text(slot.tier(), "-"), slot.count());
        }
        RerollSpec reroll = board.reroll();
        if (reroll != null) {
            CommerceAdminMessages.detail(ctx, reroll.isPaid() ? "boards.reroll.paid" : "boards.reroll",
                    reroll.maxPerPeriod());
        }
        if (!withContracts) {
            return;
        }
        for (BountyRef member : members) {
            CommerceAdminMessages.detail(ctx, "boards.contract", member.bountyId(),
                    text(member.difficultyOn(board.boardId()), "-"));
        }
    }

    /** Every enabled contract naming this board, which is what the draw picks from. */
    @Nonnull
    private static List<BountyRef> membersOf(@Nonnull String boardId) {
        List<BountyRef> members = new ArrayList<>();
        for (BountyRef ref : AssetBoardCatalog.getInstance().pool()) {
            if (ref != null && ref.enabled() && ref.isOn(boardId)) {
                members.add(ref);
            }
        }
        return members;
    }

    private static long minutes(@Nonnull RotationSpec rotation, long nowMs) {
        return Math.max(0L, rotation.millisUntilNext(nowMs) / 60_000L);
    }

    @Nonnull
    private static String text(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
