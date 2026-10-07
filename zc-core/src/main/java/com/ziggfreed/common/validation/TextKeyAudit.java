package com.ziggfreed.common.validation;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.LangCatalog;

/**
 * The one check every content validator makes of a localization key its file names: does any lang
 * file the server loaded ship it? A key nothing ships never fails a load. The surface hands the client
 * a key it cannot resolve, and the player reads the raw key, or whatever fallback that surface has,
 * instead of the words the author meant.
 *
 * <p>Asked of the loaded catalogue the way every surface resolves an authored key
 * ({@link ContentKeys#known}: exactly, or under any loaded namespace), so a key a pack writes without
 * its file's namespace is shipped when the surface would find it. With no catalogue loaded at all (a
 * unit JVM, or before the engine's i18n module is up) nothing can be told, and cannot tell is not
 * "missing": every key then reads as shipped.
 *
 * <p>Every finding is a WARNING under {@link #UNKNOWN_TEXT_KEY} whichever domain files it, so one
 * filter covers a calendar banner, a title and an Almanac line alike.
 */
public final class TextKeyAudit {

    /** The code an authored key no loaded lang file ships is filed under, in every domain. */
    public static final String UNKNOWN_TEXT_KEY = "UNKNOWN_TEXT_KEY";

    private TextKeyAudit() {
    }

    /**
     * The live answer to "does a loaded lang file ship this key?", read once per call: the loaded
     * catalogue's, or every key shipped while no catalogue is loaded.
     */
    @Nonnull
    public static Predicate<String> liveCatalogue() {
        return LangCatalog.catalogue().isEmpty() ? key -> true : ContentKeys::known;
    }

    /**
     * Report {@code key} as a WARNING when {@code shipped} says no lang file carries it; a blank key is
     * no key, and a {@code shipped} that throws reads as shipped. {@code where} names the leaf ("the
     * calendar event 'spring_fair' Herald.Start.TitleKey") and {@code cost} says what a player reads
     * instead ("the banner shows the raw key").
     */
    public static void check(@Nonnull List<Finding> out, @Nonnull String domain, @Nonnull String sourceId,
            @Nonnull String where, @Nullable String key, @Nonnull Predicate<String> shipped, @Nonnull String cost) {
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        if (!shipped(trimmed, shipped)) {
            out.add(Finding.warning(domain, UNKNOWN_TEXT_KEY, where + " names the key '" + trimmed
                    + "', which no loaded lang file ships, so " + cost, sourceId));
        }
    }

    /**
     * {@code shipped}'s answer for {@code key}, for a validator asking a fallback ladder rather than
     * reporting one key; a {@code shipped} that throws reads as shipped.
     */
    public static boolean shipped(@Nonnull String key, @Nonnull Predicate<String> shipped) {
        try {
            return shipped.test(key);
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }
}
