package com.ziggfreed.common.dialogue.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The consumer-supplied namespace + key-existence probe the dialogue page uses to
 * localize node text and option labels. 1.4.0 localization is CLIENT-resolved: the
 * page emits {@code Message.translation(keyPrefix + key)} and the client renders it
 * in the player's locale; the only things that vary per consumer are the namespace
 * prefix (the {@code .lang} filename stem, e.g. {@code "mmoskilltree."} vs
 * {@code "kweebecnightmare."}) and which keys exist (so explicit-vs-convention can
 * be decided without reading any player's locale).
 *
 * <p>A ready-made {@link I18nModuleDialogueI18n} covers the common case; an MMO
 * can supply its own to honor an in-code English fallback during early startup.
 */
public interface DialogueI18n {

    /** The {@code .lang} filename stem plus a dot (e.g. {@code "kweebecnightmare."}). */
    @Nonnull String keyPrefix();

    /**
     * True when this UNPREFIXED key resolves in the default (English) locale - a
     * locale-independent probe used to pick explicit-vs-convention without reading
     * the player's locale. The implementation prepends {@link #keyPrefix()}.
     */
    boolean hasKey(@Nonnull String unprefixedKey);

    /**
     * The default-locale (English) VALUE for this UNPREFIXED key, or {@code null} if it
     * does not resolve / the implementation does not support it. Used ONLY to detect +
     * parse inline rich-text markup (see {@link RichText}) for a custom-page Label's
     * {@code TextSpans}; the resulting span tree bakes that one locale, so a value with
     * markup is single-locale by design. Plain (markup-free) values never call this and
     * stay client-resolved per-locale. Defaults to {@code null} (no rich text) so an
     * existing {@link DialogueI18n} keeps working unchanged.
     */
    @Nullable
    default String english(@Nonnull String unprefixedKey) {
        return null;
    }
}
