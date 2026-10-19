package com.ziggfreed.common.track.boost;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.metadata.Metadata;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.effect.buff.BuffScope;

/**
 * Whose a boost is: one word of two, matched without regard to case. A closed vocabulary of one axis (who it lifts),
 * so a file writes it as a word.
 *
 * <ul>
 *   <li>{@link #PERSONAL}: it lifts the player who started it, and counts against their own limit;</li>
 *   <li>{@link #SERVER}: it lifts every player on the server, and counts against the server's limit.</li>
 * </ul>
 * An unknown word reads as {@link #PERSONAL}.
 */
public enum BoostScope {

    PERSONAL("Personal"),
    SERVER("Server");

    /** The sentence the Asset Editor shows beside a {@code Scope} leaf. */
    static final String DOCUMENTATION = "Who the boost lifts: Personal lifts the player who started it, Server "
            + "lifts every player on the server. Personal when unauthored.";

    private final String word;

    BoostScope(@Nonnull String word) {
        this.word = word;
    }

    /** The word a file writes. */
    @Nonnull
    public String word() {
        return word;
    }

    /** The authored word, {@link #PERSONAL} for null, blank or a word that is neither. */
    @Nonnull
    public static BoostScope of(@Nullable String authored) {
        if (authored != null && SERVER.word.toLowerCase(Locale.ROOT).equals(authored.trim().toLowerCase(Locale.ROOT))) {
            return SERVER;
        }
        return PERSONAL;
    }

    /** The buff bar's pool this scope's tiles take when the boost names no effect of its own. */
    @Nonnull
    public BuffScope buffScope() {
        return this == SERVER ? BuffScope.SERVER : BuffScope.PERSONAL;
    }

    /** The editor hint for a {@code Scope} leaf: the two words, each with its meaning. */
    @Nonnull
    static Metadata editorHint() {
        return EditorSchema.oneOfDocumented(
                PERSONAL.word, "Lifts the player who started it.",
                SERVER.word, "Lifts every player on the server.");
    }
}
