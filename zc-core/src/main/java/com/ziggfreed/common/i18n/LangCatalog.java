package com.ziggfreed.common.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

/**
 * Does the engine's loaded translation catalogue carry this id, and what does it say?
 *
 * <p>Probed against English only, and that is deliberate rather than a shortcut: this library holds
 * no per-player locale (the server never reads, caches or persists one), so a probe exists purely to
 * decide WHICH id to hand a client, and a server-side VALUE read exists purely for a sink that takes
 * one string for every viewer. The client still resolves display text in the viewer's own language.
 *
 * <p>Never throws. A unit JVM, or the window before the module comes up, simply has no catalogue,
 * and "no catalogue" reads as "no key" so a caller falls through to its own fallback instead of
 * dying on a lookup.
 */
public final class LangCatalog {

    /** The probe language: the engine's bundled English catalogue. */
    public static final String PROBE_LANGUAGE = "en-US";

    private LangCatalog() {
    }

    /** True when {@code fullKey} - the whole registered id, namespace included - has a value. */
    public static boolean has(@Nonnull String fullKey) {
        return value(fullKey) != null;
    }

    /**
     * The probe language's value for {@code fullKey} - the whole registered id, namespace included -
     * exactly as authored ({@code {0}}-style slots unsubstituted), or null when the catalogue does
     * not carry it (or there is no catalogue at all).
     */
    @Nullable
    public static String value(@Nonnull String fullKey) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n == null ? null : i18n.getMessage(PROBE_LANGUAGE, fullKey);
        } catch (Throwable t) {
            return null;
        }
    }
}
