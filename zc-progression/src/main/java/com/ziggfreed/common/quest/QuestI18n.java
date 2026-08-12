package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The consumer-supplied namespace plus key-existence probe a surface uses to name a quest, its
 * description, and its objectives.
 *
 * <p>Display text is CLIENT-resolved: a surface emits a translation key and the player's own client
 * renders it in their locale, so nothing here ever reads or stores a per-player language. The two
 * things that genuinely vary per consumer are the namespace prefix (its language-file stem) and
 * which keys actually exist - the second is what lets a caller choose between an explicitly authored
 * key and a by-convention one without consulting any player's locale.
 *
 * <p>The convention keys are built by {@link #titleKey}, {@link #descriptionKey}, and
 * {@link #objectiveKey} so every consumer spells them the same way, and
 * {@link #pickKey} applies the standard precedence: an explicit key that resolves, else the
 * convention key that resolves, else nothing.
 */
public interface QuestI18n {

    /** A no-op probe: no prefix, no key ever resolves. Surfaces then fall back to raw text. */
    QuestI18n NONE = new QuestI18n() {
        @Override
        @Nonnull
        public String keyPrefix() {
            return "";
        }

        @Override
        public boolean hasKey(@Nonnull String unprefixedKey) {
            return false;
        }
    };

    /** The language-file stem plus a dot (e.g. {@code "yourmod."}). */
    @Nonnull
    String keyPrefix();

    /**
     * Does this UNPREFIXED key resolve in the default locale? A locale-independent probe, used to
     * pick explicit-versus-convention without reading any player's language. Implementations prepend
     * {@link #keyPrefix()} themselves.
     */
    boolean hasKey(@Nonnull String unprefixedKey);

    /** The full, prefixed key a surface hands to the client. */
    @Nonnull
    default String fullKey(@Nonnull String unprefixedKey) {
        return keyPrefix() + unprefixedKey;
    }

    /** The by-convention key for a quest's name. */
    @Nonnull
    default String titleKey(@Nonnull String questId) {
        return "quest." + questId + ".title";
    }

    /** The by-convention key for a quest's description. */
    @Nonnull
    default String descriptionKey(@Nonnull String questId) {
        return "quest." + questId + ".desc";
    }

    /** The by-convention key for one objective's text. */
    @Nonnull
    default String objectiveKey(@Nonnull String questId, @Nonnull String objectiveId) {
        return "quest." + questId + ".objective." + objectiveId;
    }

    /**
     * The standard precedence: an explicit authored key if it resolves, else the by-convention key
     * if it resolves, else null (the caller falls back to whatever raw text it has). Both arguments
     * are UNPREFIXED.
     */
    @Nullable
    default String pickKey(@Nullable String explicitKey, @Nullable String conventionKey) {
        if (explicitKey != null && !explicitKey.isEmpty() && hasKey(explicitKey)) {
            return explicitKey;
        }
        if (conventionKey != null && !conventionKey.isEmpty() && hasKey(conventionKey)) {
            return conventionKey;
        }
        return null;
    }
}
