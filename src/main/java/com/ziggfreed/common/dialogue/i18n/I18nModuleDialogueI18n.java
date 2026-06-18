package com.ziggfreed.common.dialogue.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

/**
 * The zero-config {@link DialogueI18n}: probes the engine's {@link I18nModule} for a
 * key's existence in the default (English) locale. A minimal consumer (a minigame)
 * just passes {@code new I18nModuleDialogueI18n("kweebecnightmare.")} and writes no
 * adapter; an MMO that wants an in-code English fallback during early startup
 * supplies its own {@link DialogueI18n} instead.
 */
public final class I18nModuleDialogueI18n implements DialogueI18n {

    private final String keyPrefix;
    private final String englishLanguage;

    /** Probe against the {@code en-US} locale (the engine's bundled English). */
    public I18nModuleDialogueI18n(@Nonnull String keyPrefix) {
        this(keyPrefix, "en-US");
    }

    public I18nModuleDialogueI18n(@Nonnull String keyPrefix, @Nonnull String englishLanguage) {
        this.keyPrefix = keyPrefix;
        this.englishLanguage = englishLanguage;
    }

    @Override
    @Nonnull
    public String keyPrefix() {
        return keyPrefix;
    }

    @Override
    public boolean hasKey(@Nonnull String unprefixedKey) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n != null && i18n.getMessage(englishLanguage, keyPrefix + unprefixedKey) != null;
        } catch (Throwable t) {
            // A unit JVM or pre-init window has no module; treat as "no key" so the
            // page falls through to the raw fallback rather than throwing.
            return false;
        }
    }

    @Override
    @Nullable
    public String english(@Nonnull String unprefixedKey) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n != null ? i18n.getMessage(englishLanguage, keyPrefix + unprefixedKey) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
