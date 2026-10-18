package com.ziggfreed.common.track.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.metadata.Metadata;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * What a prestige does to the build a player made on a track (the ruled setting, R641): one of three words,
 * matched without regard to case. The track core only keeps the setting; the picks a prestige keeps,
 * remembers or clears are the picks pass's and the prestige content's to act on.
 *
 * <ul>
 *   <li>{@link #KEEP}: every pick stays, the default for combat;</li>
 *   <li>{@link #REMEMBER}: picks clear, and come back preselected as the player levels again, the default for
 *       gathering and crafting;</li>
 *   <li>{@link #CLEAR}: picks clear for a fresh choice.</li>
 * </ul>
 * An unknown word reads as unwritten, so the next layer down answers (the category's, then {@link #KEEP}).
 */
public enum PrestigeBuild {

    KEEP("Keep"),
    REMEMBER("Remember"),
    CLEAR("Clear");

    /** The Asset Editor's dropdown for a {@code Build} leaf, with what each word does. */
    public static final String DOCUMENTATION = "What a prestige does to the picks made on this track: Keep "
            + "leaves every pick in place, Remember clears them and offers each again preselected as the level "
            + "comes back, Clear clears them for a fresh choice.";

    private final String word;

    PrestigeBuild(@Nonnull String word) {
        this.word = word;
    }

    /** The word a file writes. */
    @Nonnull
    public String word() {
        return word;
    }

    /** The authored word, or null for null, blank or a word that is none of the three. */
    @Nullable
    public static PrestigeBuild of(@Nullable String authored) {
        if (authored == null || authored.isBlank()) {
            return null;
        }
        String wanted = authored.trim().toLowerCase(Locale.ROOT);
        for (PrestigeBuild build : values()) {
            if (build.word.toLowerCase(Locale.ROOT).equals(wanted)) {
                return build;
            }
        }
        return null;
    }

    /** The editor hint for a {@code Build} leaf: the three words, each with its meaning. */
    @Nonnull
    static Metadata editorHint() {
        return EditorSchema.oneOfDocumented(
                KEEP.word, "Every pick stays as it was.",
                REMEMBER.word, "Picks clear and come back preselected as the player levels again.",
                CLEAR.word, "Picks clear for a fresh choice.");
    }
}
