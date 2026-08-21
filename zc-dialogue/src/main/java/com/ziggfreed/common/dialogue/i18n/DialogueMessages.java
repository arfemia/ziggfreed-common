package com.ziggfreed.common.dialogue.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

/**
 * Builds the client-resolved {@link Message}s the conversation page needs.
 *
 * <p>There are two kinds of text on a conversation screen and they are answered differently.
 * AUTHORED text - a screen's line, an option's label - carries a key its author wrote, and the mod
 * that ships a value for that key is the mod whose namespace it resolves in
 * ({@link ContentKeys}). The PAGE's own text - the exit row, the nothing-to-say line - is the
 * library's, so it resolves in the library's namespace, and a conversation with a voice of its own
 * overrides it by authored key instead.
 *
 * <p><b>Who ships the key IS the attribution.</b> Nothing on a conversation records which jar wrote
 * it, and asking the mod that happened to open the screen was the old answer, which is wrong the
 * moment a server runs two talking mods: a conversation opened by one mod would have every one of
 * another mod's keys miss, and every line on the screen would read as a raw key. Asking whoever
 * ships the key is both the only attribution available and the one that makes the text right.
 *
 * <p>A localized arg is passed as a NESTED {@link Message} so it too resolves in the viewer's
 * language; data (numbers, ids) is a flat param.
 */
public final class DialogueMessages {

    /**
     * The library's own lang namespace. {@code I18nModule} takes a pack key's prefix from the lang
     * FILENAME and keeps its dots, so {@code ziggfreedcommon.dialogue.lang} registers
     * {@code ziggfreedcommon.dialogue.*} and its entries are written unprefixed.
     */
    private static final String PAGE_PREFIX = "ziggfreedcommon.dialogue.";

    private DialogueMessages() {
    }

    /**
     * One of the PAGE's own lines, in the library's namespace: {@code farewell}, {@code missing},
     * {@code active_objective}. For text an author wrote, use {@link #resolve} instead.
     */
    @Nonnull
    public static Message page(@Nonnull String unprefixedKey, @Nonnull Object... args) {
        return Msg.key(PAGE_PREFIX + unprefixedKey, args);
    }

    /**
     * One of the page's own lines, unless this conversation authored its own key for it. The
     * authored key resolves like any other authored key; an unauthored one takes the library's
     * wording.
     */
    @Nonnull
    public static Message page(@Nullable String authoredKey, @Nonnull String unprefixedKey) {
        Message authored = authoredKey == null || authoredKey.isBlank()
                ? null : ContentKeys.tr(authoredKey);
        return authored != null ? authored : page(unprefixedKey);
    }

    /** Raw, untranslated text (never a localized name). */
    @Nonnull
    public static Message raw(@Nullable String text) {
        return Msg.raw(text);
    }

    /**
     * AUTHORED content text: the explicit key its author wrote if anything ships it, else the
     * by-convention key if anything ships that, else the raw literal the file carried, else null
     * (the page then shows a debug marker). Keys are written unprefixed and resolve in the namespace
     * of whichever mod ships them.
     */
    @Nullable
    public static Message resolve(@Nullable String explicitKey, @Nullable String conventionKey,
            @Nullable String rawFallback) {
        Message explicit = known(explicitKey);
        if (explicit != null) {
            return explicit;
        }
        Message convention = known(conventionKey);
        if (convention != null) {
            return convention;
        }
        return (rawFallback != null && !rawFallback.isEmpty()) ? Msg.raw(rawFallback) : null;
    }

    /** The key resolved through whichever registered mod ships it, or null when none does. */
    @Nullable
    private static Message known(@Nullable String authoredKey) {
        if (authoredKey == null || authoredKey.isEmpty() || !ContentKeys.known(authoredKey)) {
            return null;
        }
        return ContentKeys.tr(authoredKey);
    }
}
