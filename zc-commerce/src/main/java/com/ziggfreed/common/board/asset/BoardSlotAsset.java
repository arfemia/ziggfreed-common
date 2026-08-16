package com.ziggfreed.common.board.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.commerce.asset.SlotAsset;

/**
 * One slot of a board's posting: the shared slot leaves ({@code Count} / {@code Optional}) plus the
 * one word a board filters on, a contract's {@code Difficulty}.
 *
 * <pre>{@code
 * "Slots": [ { "Difficulty": "Training", "Count": 2 },
 *            { "Difficulty": "Easy" },
 *            { "Difficulty": "Skirmish" },
 *            { "Difficulty": "Hard", "Optional": true } ]
 * }</pre>
 *
 * <p>Slots are what make a board READ the way it was designed - two easy contracts a newcomer can
 * take plus one hard one worth coming back for - rather than three draws that might all land in the
 * same band. Mark the rarest band {@code Optional} so a thin catalogue leaves no visible gap.
 */
public final class BoardSlotAsset extends SlotAsset {

    @Nullable protected String difficulty;

    public static final BuilderCodec<BoardSlotAsset> CODEC =
            appendLeaves(BuilderCodec.builder(BoardSlotAsset.class, BoardSlotAsset::new))
                    .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                            (o, v) -> o.difficulty = v, o -> o.difficulty,
                            (o, p) -> o.difficulty = p.difficulty)
                    .documentation("Only post contracts whose own Boards entry carries this band. It is a free "
                            + "label the content invents - training, easy, normal, hard - matched however it is "
                            + "capitalized. Unauthored posts anything the board holds.").add()
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

    /** The candidate label this slot posts, lower-cased for matching, or null for anything. */
    @Override
    @Nullable
    public String label() {
        String authored = getDifficulty();
        return authored == null ? null : authored.toLowerCase(Locale.ROOT);
    }
}
