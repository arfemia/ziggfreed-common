package com.ziggfreed.common.objectives.dialogue;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.commerce.asset.WhereAxis;
import com.ziggfreed.common.commerce.fold.AssetBoardCatalog;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.dialogue.type.DialogueConditionType;
import com.ziggfreed.common.objectives.gate.RequiresCheck;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * {@code HasActiveBounties}: passes when the player can reach a board that has work posted for them right
 * now, the question a conversation asks before offering to send somebody to one, so a line pointing at the
 * boards hides while there is nothing there to take.
 *
 * <pre>{@code
 * { "LabelKey": "yourpack.npc.boards", "Open": { "Type": "Board", "Board": "Yourpack_Daily" },
 *   "Conditions": [ { "Type": "HasActiveBounties" } ] }
 * }</pre>
 *
 * <p>Only the boards in the player's own world are asked ({@link AssetBoardCatalog#boardsIn}, so a board
 * whose {@code Where} leaves that world out never answers yes there), and of those only one that is
 * enabled, whose own {@code Requires} the player meets, and whose active set for the player is not empty
 * (the board engine's own draw, rerolls included). The player is the runtime's own subject, the one every
 * board surface reads, so the answer is the board page's. Nothing to draw for (no player the runtime can
 * name) answers no. Param-less, and it only reads.
 */
public final class HasActiveBountiesDialogueCondition {

    /** The authored {@code "Type"}. */
    public static final String TYPE_ID = "HasActiveBounties";

    private HasActiveBountiesDialogueCondition() {
    }

    /** {@code {"Type": "HasActiveBounties"}}; it carries nothing. */
    public static final class HasActiveBounties extends DialogueCondition {

        public static final BuilderCodec<HasActiveBounties> CODEC =
                BuilderCodec.builder(HasActiveBounties.class, HasActiveBounties::new).build();
    }

    /** The registration: the Type, its codec and its evaluator. */
    @Nonnull
    public static DialogueConditionType<HasActiveBounties> type() {
        return DialogueConditionType.of(TYPE_ID, HasActiveBounties.class, HasActiveBounties.CODEC,
                (HasActiveBounties condition, DialogueContext ctx) -> evaluate(ctx));
    }

    /** The player in {@code ctx}, in the world their store stands in. World thread. */
    private static boolean evaluate(@Nonnull DialogueContext ctx) {
        Subject subject = RequiresCheck.playerSubject(ctx.store(), ctx.ref());
        return subject != null && anyPosted(subject, WhereAxis.viewer(ctx.store()), System.currentTimeMillis());
    }

    /**
     * Whether {@code subject}, standing in {@code viewer}'s world at {@code nowMs}, can reach an enabled board
     * whose {@code Requires} they meet and whose active set for them is not empty. No subject answers no.
     */
    static boolean anyPosted(@Nullable Subject subject, @Nonnull LoadedWorld viewer, long nowMs) {
        if (subject == null) {
            return false;
        }
        AssetBoardCatalog catalog = CommerceCatalogs.boards();
        List<BoardAssetSpec> boards = catalog.boardsIn(viewer);
        if (boards.isEmpty()) {
            return false;
        }
        GateEvaluator gates = CommerceEngines.gates();
        BoardEngine engine = CommerceEngines.boards();
        Collection<BountyRef> pool = catalog.pool();
        for (BoardAssetSpec board : boards) {
            if (board.enabled() && gates.passes(subject, board.requires())
                    && !engine.activeSetFor(subject, board, pool, nowMs).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
