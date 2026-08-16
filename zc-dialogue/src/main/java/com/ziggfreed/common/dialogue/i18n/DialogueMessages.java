package com.ziggfreed.common.dialogue.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

/**
 * Builds the client-resolved {@link Message}s the dialogue page needs, given the consumer's
 * {@link ContentI18n}. The namespace and the key probe are injected rather than singleton-global, so
 * the lifted page has zero consumer-i18n coupling. A localized arg is passed as a NESTED
 * {@link Message} so it too resolves in the viewer's locale; data (numbers, ids) is a flat param.
 */
public final class DialogueMessages {

    private DialogueMessages() {
    }

    /** A translation {@link Message} for an unprefixed key (prefix applied), {@code {0},{1},...} bound. */
    @Nonnull
    public static Message tr(@Nonnull ContentI18n i18n, @Nonnull String unprefixedKey,
            @Nonnull Object... args) {
        return Msg.key(i18n.keyPrefix() + unprefixedKey, args);
    }

    /** Raw, untranslated text (never a localized name). */
    @Nonnull
    public static Message raw(@Nullable String text) {
        return Msg.raw(text);
    }

    /**
     * Content-text precedence: explicit author key (if it resolves) -> by-convention key (if it
     * resolves) -> raw literal fallback -> null. Explicit/convention keys are prefix-free;
     * {@link #tr} adds the namespace. Returns null when nothing resolves and there is no raw
     * fallback (the page then shows a debug marker).
     */
    @Nullable
    public static Message resolve(@Nonnull ContentI18n i18n, @Nullable String explicitKey,
                                  @Nullable String conventionKey, @Nullable String rawFallback) {
        String key = ContentKeys.pick(i18n, explicitKey, conventionKey);
        if (key != null) {
            return tr(i18n, key);
        }
        return (rawFallback != null && !rawFallback.isEmpty()) ? Msg.raw(rawFallback) : null;
    }
}
