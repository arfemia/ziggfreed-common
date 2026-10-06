package com.ziggfreed.common.board.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.commerce.asset.SlotAsset;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * One slot of a board's posting: the shared slot leaves ({@code Count} / {@code Optional}) plus the
 * one word a board filters on, a contract's {@code Difficulty}.
 *
 * <pre>{@code
 * "Slots": [ { "Difficulty": "Training", "Count": 2 },
 *            { "Difficulty": "Easy" },
 *            { "Difficulty": "Easy", "Optional": true,
 *              "Requires": { "Factors": [ { "Factor": "yourmod:standing", "Min": 1 } ] } },
 *            { "Difficulty": "Hard", "Optional": true } ]
 * }</pre>
 *
 * <p>Slots are what make a board READ the way it was designed - two easy contracts a newcomer can
 * take plus one hard one worth coming back for - rather than three draws that might all land in the
 * same band. Mark the rarest band {@code Optional} so a thin catalogue leaves no visible gap. A slot's
 * own {@code Requires} opens a second posting of a band to the players who have earned it: everyone
 * sees it, only they may take it.
 */
public final class BoardSlotAsset extends SlotAsset {

    @Nullable protected String difficulty;
    @Nullable protected GateSpec requires;

    public static final BuilderCodec<BoardSlotAsset> CODEC =
            appendLeaves(BuilderCodec.builder(BoardSlotAsset.class, BoardSlotAsset::new))
                    .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                            (o, v) -> o.difficulty = v, o -> o.difficulty,
                            (o, p) -> o.difficulty = p.difficulty)
                    .documentation("Only post contracts whose own Boards entry carries this band. It is a free "
                            + "label the content invents - training, easy, normal, hard - matched however it is "
                            + "capitalized. Unauthored posts anything the board holds.").add()
                    .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                            (o, v) -> o.requires = v, o -> o.requires, (o, p) -> o.requires = p.requires)
                    .documentation("What a player must be before they may take the contract this slot posts, "
                            + "whatever band it carries. The board is drawn for the whole server, so the slot is "
                            + "always posted; a player who fails this sees its contract locked with the reason. "
                            + "The ordinary Requires block, checked after the board's own and the band's "
                            + "AcceptRequires, and a lock only: nothing here hides the slot. Unauthored asks for "
                            + "nothing.").add()
                    .build();

    public BoardSlotAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static BoardSlotAsset of(@Nullable String difficulty, @Nullable Integer count,
            @Nullable Boolean optional) {
        BoardSlotAsset s = new BoardSlotAsset();
        s.difficulty = difficulty;
        s.count = count;
        s.optional = optional;
        return s;
    }

    /** The authored band exactly as written, or null for "anything the board holds". */
    @Nullable
    public String getDifficulty() {
        return difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
    }

    /** What a player must be to take this slot's contract, or null when the slot is open to everybody. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The candidate label this slot posts, lower-cased for matching, or null for anything. */
    @Override
    @Nullable
    public String label() {
        String authored = getDifficulty();
        return authored == null ? null : authored.toLowerCase(Locale.ROOT);
    }
}
