package com.ziggfreed.common.board.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.commerce.asset.SlotAsset;
import com.ziggfreed.common.progress.asset.ContentTextAsset;

/**
 * One slot of a board's posting: the shared slot leaves ({@code Count} / {@code Optional}) plus the
 * one word a board filters on, a contract's {@code Difficulty}, and what that word READS as.
 *
 * <pre>{@code
 * "Slots": [ { "Difficulty": "Training", "Count": 2 },
 *            { "Difficulty": "Easy" },
 *            { "Difficulty": "Skirmish", "Text": { "TitleKey": "board.grade.skirmish" } },
 *            { "Difficulty": "Hard", "Optional": true } ]
 * }</pre>
 *
 * <p>Slots are what make a board READ the way it was designed - two easy contracts a newcomer can
 * take plus one hard one worth coming back for - rather than three draws that might all land in the
 * same band. Mark the rarest band {@code Optional} so a thin catalogue leaves no visible gap.
 *
 * <p><b>{@code Text} is what the band is CALLED on screen</b>, and it is the reason a board may
 * invent any word it likes. The common bands already read in every language without anybody writing
 * a key; a band of your own reads in words the moment you point one at your own lang file, and reads
 * as its own word until you do.
 */
public final class BoardSlotAsset extends SlotAsset {

    @Nullable protected String difficulty;
    @Nullable protected ContentTextAsset text;

    public static final BuilderCodec<BoardSlotAsset> CODEC =
            appendLeaves(BuilderCodec.builder(BoardSlotAsset.class, BoardSlotAsset::new))
                    .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                            (o, v) -> o.difficulty = v, o -> o.difficulty,
                            (o, p) -> o.difficulty = p.difficulty)
                    .documentation("Only post contracts whose own Boards entry carries this band. It is a free "
                            + "label the content invents - training, easy, normal, hard - matched however it is "
                            + "capitalized. Unauthored posts anything the board holds.").add()
                    .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                            (o, v) -> o.text = v, o -> o.text, (o, p) -> o.text = p.text)
                    .documentation("What this band is CALLED wherever a contract's grade is shown, as a "
                            + "localization key in your own lang file. Unauthored, a band with a name of its own "
                            + "already reads in words; a band you invented reads as the word you wrote it as, so "
                            + "point TitleKey at a line of yours to have it read in every language.").add()
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

    /** {@link #of(String, Integer, Boolean)} for a band that also says what it is called. */
    @Nonnull
    public static BoardSlotAsset of(@Nullable String difficulty, @Nullable Integer count,
            @Nullable Boolean optional, @Nullable ContentTextAsset text) {
        BoardSlotAsset s = of(difficulty, count, optional);
        s.text = text;
        return s;
    }

    /** The authored band exactly as written, or null for "anything the board holds". */
    @Nullable
    public String getDifficulty() {
        return difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
    }

    /** What this band is called on screen, or null when nothing here names it. */
    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The candidate label this slot posts, lower-cased for matching, or null for anything. */
    @Override
    @Nullable
    public String label() {
        String authored = getDifficulty();
        return authored == null ? null : authored.toLowerCase(Locale.ROOT);
    }
}
