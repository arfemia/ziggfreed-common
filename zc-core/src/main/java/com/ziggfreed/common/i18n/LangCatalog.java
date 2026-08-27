package com.ziggfreed.common.i18n;

import java.util.Map;

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

    /**
     * The test stand-in for the engine catalogue, or null for the real one. It lives HERE, on the
     * one facade every probe already routes through, because the surfaces that resolve authored
     * keys ({@link ContentKeys} and everything built on it) run in consuming modules' unit JVMs
     * where no engine module exists to load a catalogue - so their tests hand this facade a plain
     * map of full registered ids instead, and the production code paths stay byte-identical.
     */
    @Nullable
    private static volatile Map<String, String> override;

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
        Map<String, String> fixed = override;
        if (fixed != null) {
            return fixed.get(fullKey);
        }
        try {
            I18nModule i18n = I18nModule.get();
            return i18n == null ? null : i18n.getMessage(PROBE_LANGUAGE, fullKey);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The whole loaded probe-language catalogue, full registered id to authored value - the live
     * answer to "which keys did the server actually load", and therefore the surface a bare
     * authored key is attributed against. Never null: no module, or a module that throws, reads as
     * an empty catalogue.
     *
     * <p>The engine returns the SAME unmodifiable map instance until a load changes its message
     * version ({@code I18nModule#getMessages} caches per version), so a caller may use the
     * instance's identity to know whether the catalogue it derived anything from is still current.
     * Per-key {@link #has}/{@link #value} probes additionally see the engine jar's own bundled
     * en-US defaults (its {@code getMessage} fallback tier); this map view does not, which only
     * affects native engine ids and those are always written fully qualified anyway.
     */
    @Nonnull
    public static Map<String, String> catalogue() {
        Map<String, String> fixed = override;
        if (fixed != null) {
            return fixed;
        }
        try {
            I18nModule i18n = I18nModule.get();
            Map<String, String> loaded = i18n == null ? null : i18n.getMessages(PROBE_LANGUAGE);
            return loaded == null ? Map.of() : loaded;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /**
     * TEST SEAM: pin the catalogue to a fixed map of full registered ids (pass null to return to
     * the real engine module). Production code must never call this - a real catalogue is the
     * engine's to load.
     */
    public static void overrideForTests(@Nullable Map<String, String> catalogue) {
        override = catalogue;
    }
}
