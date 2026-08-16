package com.ziggfreed.common.i18n;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

/**
 * Does the engine's loaded translation catalogue carry this id?
 *
 * <p>Probed against English only, and that is deliberate rather than a shortcut: this library holds
 * no per-player locale (the server never reads, caches or persists one), so a probe exists purely to
 * decide WHICH id to hand a client. The client still resolves it in the viewer's own language.
 *
 * <p>Never throws. A unit JVM, or the window before the module comes up, simply has no catalogue,
 * and "no catalogue" reads as "no key" so a caller falls through to its own fallback instead of
 * dying on a lookup.
 */
final class LangCatalog {

    /** The probe language: the engine's bundled English catalogue. */
    static final String PROBE_LANGUAGE = "en-US";

    private LangCatalog() {
    }

    /** True when {@code fullKey} - the whole registered id, namespace included - has a value. */
    static boolean has(@Nonnull String fullKey) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n != null && i18n.getMessage(PROBE_LANGUAGE, fullKey) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
