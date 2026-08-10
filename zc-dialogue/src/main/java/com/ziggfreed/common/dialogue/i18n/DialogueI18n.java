package com.ziggfreed.common.dialogue.i18n;

import javax.annotation.Nonnull;

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
}
