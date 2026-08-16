package com.ziggfreed.common.i18n;

import javax.annotation.Nonnull;

/**
 * The zero-config {@link ContentI18n}: a namespace plus the engine catalogue's own answer about what
 * exists in it. A mod whose keys all live in shipped {@code .lang} files needs nothing more than
 * {@code new I18nModuleContentI18n("kweebecnightmare.")} and writes no adapter of its own.
 *
 * <p>A consumer that ALSO holds keys the catalogue cannot see yet - an in-code English baseline
 * consulted during early startup, say - supplies its own {@link ContentI18n} instead, so its
 * existence answer covers both places a key can live.
 */
public final class I18nModuleContentI18n implements ContentI18n {

    private final String keyPrefix;

    public I18nModuleContentI18n(@Nonnull String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    @Override
    @Nonnull
    public String keyPrefix() {
        return keyPrefix;
    }

    @Override
    public boolean hasKey(@Nonnull String unprefixedKey) {
        return LangCatalog.has(keyPrefix + unprefixedKey);
    }
}
