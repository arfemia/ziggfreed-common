package com.ziggfreed.common.dialogue.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * Builds the client-resolved {@link Message}s the dialogue page needs, given a
 * {@link DialogueI18n}. Mirrors an MMO's {@code Msg}+{@code LocalizedText} but with
 * the namespace prefix + key probe injected rather than singleton-global, so the
 * lifted page has zero consumer-i18n coupling. A localized arg is passed as a
 * NESTED {@link Message} so it too resolves in the viewer's locale; data (numbers,
 * ids) is a flat param.
 */
public final class DialogueMessages {

    private DialogueMessages() {
    }

    /** A translation {@link Message} for an unprefixed key (prefix applied), {@code {0},{1},...} bound. */
    @Nonnull
    public static Message tr(@Nonnull DialogueI18n i18n, @Nonnull String unprefixedKey, @Nonnull Object... args) {
        Message m = Message.translation(i18n.keyPrefix() + unprefixedKey);
        bindAll(m, args);
        return m;
    }

    /** Raw, untranslated text (never a localized name). */
    @Nonnull
    public static Message raw(@Nullable String text) {
        return Message.raw(text != null ? text : "");
    }

    /**
     * Content-text precedence: explicit author key (if it resolves) -> by-convention
     * key (if it resolves) -> raw literal fallback -> null. Explicit/convention keys
     * are prefix-free; {@link #tr} adds the namespace. Returns null when nothing
     * resolves and there is no raw fallback (the page then shows a debug marker).
     */
    @Nullable
    public static Message resolve(@Nonnull DialogueI18n i18n, @Nullable String explicitKey,
                                  @Nullable String conventionKey, @Nullable String rawFallback) {
        String key = pickKey(i18n, explicitKey, conventionKey);
        if (key != null) {
            return tr(i18n, key);
        }
        return (rawFallback != null && !rawFallback.isEmpty()) ? Message.raw(rawFallback) : null;
    }

    @Nullable
    private static String pickKey(@Nonnull DialogueI18n i18n, @Nullable String explicit, @Nullable String convention) {
        if (explicit != null && !explicit.isEmpty() && i18n.hasKey(explicit)) {
            return explicit;
        }
        if (convention != null && !convention.isEmpty() && i18n.hasKey(convention)) {
            return convention;
        }
        return null;
    }

    private static void bindAll(@Nonnull Message m, @Nonnull Object... args) {
        for (int i = 0; i < args.length; i++) {
            bind(m, Integer.toString(i), args[i]);
        }
    }

    private static void bind(@Nonnull Message m, @Nonnull String key, @Nullable Object arg) {
        switch (arg) {
            case null -> m.param(key, "");
            case Message msg -> m.param(key, msg);
            case Integer i -> m.param(key, (int) i);
            case Long l -> m.param(key, (long) l);
            case Double d -> m.param(key, (double) d);
            case Float f -> m.param(key, (float) f);
            case Boolean b -> m.param(key, (boolean) b);
            default -> m.param(key, arg.toString());
        }
    }
}
