package com.ziggfreed.common.track.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.metadata.Metadata;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * How a derived track reads its sources (a track's {@code Derived.Reading}): one of two words, matched without regard
 * to case, a closed vocabulary.
 *
 * <ul>
 *   <li>{@link #HIGHEST}: the highest source's level, and a write lands on that source (the first listed on a tie);</li>
 *   <li>{@link #SUM}: the sum of the sources' levels, and a write is refused, since no one source holds it.</li>
 * </ul>
 * An unknown word reads as unwritten, which is {@link #HIGHEST}.
 */
public enum DerivedReading {

    HIGHEST("Highest"),
    SUM("Sum");

    /** What the Asset Editor says beside a {@code Reading} leaf. */
    public static final String DOCUMENTATION = "How this track reads its sources: Highest is the level of the "
            + "highest one, and a gain or a set lands on that one (the first listed when two are level); Sum adds "
            + "their levels together, and a gain or a set on it is refused, since no one source holds it. Highest "
            + "when unauthored.";

    private final String word;

    DerivedReading(@Nonnull String word) {
        this.word = word;
    }

    /** The word a file writes. */
    @Nonnull
    public String word() {
        return word;
    }

    /** The authored word, or null for null, blank or a word that is neither. */
    @Nullable
    public static DerivedReading of(@Nullable String authored) {
        if (authored == null || authored.isBlank()) {
            return null;
        }
        String wanted = authored.trim().toLowerCase(Locale.ROOT);
        for (DerivedReading reading : values()) {
            if (reading.word.toLowerCase(Locale.ROOT).equals(wanted)) {
                return reading;
            }
        }
        return null;
    }

    /** The editor hint for a {@code Reading} leaf: the two words, each with its meaning. */
    @Nonnull
    static Metadata editorHint() {
        return EditorSchema.oneOfDocumented(
                HIGHEST.word, "The level of the highest source; a write lands on it.",
                SUM.word, "The sources' levels added together; a write is refused.");
    }
}
