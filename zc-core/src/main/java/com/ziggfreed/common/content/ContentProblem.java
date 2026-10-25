package com.ziggfreed.common.content;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * Why the server content pack refused a save, carried by a {@link ContentRefusedException} on a
 * result's completion. Each has the sentence an editor shows the admin.
 */
public enum ContentProblem {
    /** The pack is not loaded, is turned off in the server config, or is read-only. */
    PACK_UNAVAILABLE,
    /** The store keeps no files in a pack (it has no folder under {@code Server/}). */
    UNSUPPORTED_STORE,
    /** The id cannot name a file: only letters, digits, {@code _} and {@code -}. */
    BAD_ID,
    /** The document is not a JSON object. */
    BAD_JSON,
    /** The store refused the asset: it does not decode, or a check on it failed (the server log says which). */
    REJECTED,
    /** The pack holds no asset by that id in that store. */
    NOT_FOUND,
    /** A text key or its words are empty, the words hold a backslash (no lang file can show one), or the key is not a lang key. */
    BAD_TEXT,
    /** Another pack defines the text key, and the engine keeps the first definition of a key. */
    TEXT_TAKEN,
    /** The file could not be read or written. */
    IO;

    private static final String PREFIX = "ziggfreedcommon.content.problem.";

    /** The sentence an editor shows for this refusal. */
    @Nonnull
    public Message message() {
        return Msg.key(PREFIX + name().toLowerCase(Locale.ROOT));
    }
}
