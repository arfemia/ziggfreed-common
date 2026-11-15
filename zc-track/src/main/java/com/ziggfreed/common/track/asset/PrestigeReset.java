package com.ziggfreed.common.track.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.metadata.Metadata;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The kinds of pick a track's {@code Prestige.Resets} group switches one by one (R852, R876), each the word a pick's
 * {@code ResetsAs} names and the leaf of the Resets group that decides it, matched without regard to case. A pick
 * that names none is a {@link #TRAINER_PICKS} pick when its {@code Where} names a trainer, else a {@link #PICKS} one.
 *
 * <ul>
 *   <li>{@link #PICKS}: the tree's own picks, bought with the track's points; reset unless written otherwise;</li>
 *   <li>{@link #MASTERY}: the Mastery row; kept unless written otherwise;</li>
 *   <li>{@link #TRAINER_PICKS}: what a trainer taught that names no kind of its own; kept unless written otherwise;</li>
 *   <li>{@link #ABILITIES}: learned abilities; kept unless written otherwise;</li>
 *   <li>{@link #TECHNIQUES}: Techniques; reset unless written otherwise.</li>
 * </ul>
 * A pick a prestige resets follows the track's {@code Prestige.Build}, Remember or Clear; one it keeps stays exactly as
 * it is. Under a Keep build nothing resets, whatever the group says (R895). An unknown word reads as unwritten.
 */
public enum PrestigeReset {

    PICKS("Picks", true),
    MASTERY("Mastery", false),
    TRAINER_PICKS("TrainerPicks", false),
    ABILITIES("Abilities", false),
    TECHNIQUES("Techniques", true);

    /** The Asset Editor's words for a pick's {@code ResetsAs} leaf. */
    public static final String DOCUMENTATION = "Which of its track's Prestige.Resets switches decides what a prestige "
            + "does to this pick: Picks (the tree's own picks), Mastery (the Mastery row), TrainerPicks (what a "
            + "trainer taught), Abilities (learned abilities) or Techniques. Unauthored, TrainerPicks for a pick "
            + "whose Where names a trainer, else Picks.";

    private final String word;
    private final boolean resetByDefault;

    PrestigeReset(@Nonnull String word, boolean resetByDefault) {
        this.word = word;
        this.resetByDefault = resetByDefault;
    }

    /** The word a file writes, and the Resets leaf that switches it. */
    @Nonnull
    public String word() {
        return word;
    }

    /** Does a prestige reset this kind of pick when the track's Resets group says nothing of it (R876)? */
    public boolean resetByDefault() {
        return resetByDefault;
    }

    /** The authored word, or null for null, blank or a word that is none of the five. */
    @Nullable
    public static PrestigeReset of(@Nullable String authored) {
        if (authored == null || authored.isBlank()) {
            return null;
        }
        String wanted = authored.trim().toLowerCase(Locale.ROOT);
        for (PrestigeReset kind : values()) {
            if (kind.word.toLowerCase(Locale.ROOT).equals(wanted)) {
                return kind;
            }
        }
        return null;
    }

    /** The editor hint for a pick's {@code ResetsAs} leaf: the five words, each with its meaning. */
    @Nonnull
    public static Metadata editorHint() {
        return EditorSchema.oneOfDocumented(
                PICKS.word, "One of the tree's own picks.",
                MASTERY.word, "A pick on the Mastery row.",
                TRAINER_PICKS.word, "Something a trainer taught.",
                ABILITIES.word, "A learned ability.",
                TECHNIQUES.word, "A Technique.");
    }
}
