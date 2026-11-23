package com.ziggfreed.common.ui.form;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.util.SafeLog;

/**
 * The previews an owner form may show under its settings ({@link OwnerFormAsset#preview()}): the mod that reads a file
 * registers a preview once at setup under an id, and a form over that file names the id, so the owner sees what the
 * values on the screen would do (the value each level of a curve would start at) before anything is saved, kept up as
 * they type. Ids match without regard to case; registering an id again replaces the preview.
 */
public final class OwnerFormPreviews {

    /** One preview: the line the values on the screen would read as, from the mod's own words. */
    @FunctionalInterface
    public interface Preview {

        /**
         * @param entryId the entry the settings sit under (an Entries form's open entry, or the form's own
         *                {@code Entry}), or null for a file of plain settings
         * @param values  each setting's path inside the entry (as the form names it) to the text in its control now,
         *                typed or shown; a blank text is a setting the owner cleared
         * @return the line to show, or null to show none (the values cannot say anything yet)
         */
        @Nullable
        Message line(@Nullable String entryId, @Nonnull Map<String, String> values);
    }

    private static final Map<String, Preview> PREVIEWS = new ConcurrentHashMap<>();

    private OwnerFormPreviews() {
    }

    /** Register (or replace) {@code preview} under {@code id}. */
    public static void register(@Nonnull String id, @Nonnull Preview preview) {
        PREVIEWS.put(key(id), preview);
    }

    /** Has an installed mod registered a preview under {@code id}? */
    public static boolean has(@Nullable String id) {
        return id != null && !id.isBlank() && PREVIEWS.containsKey(key(id));
    }

    /**
     * The line the preview {@code id} names reads for these values, or null when no installed mod registered it, it
     * has nothing to say, or it threw (one warning).
     */
    @Nullable
    public static Message line(@Nullable String id, @Nullable String entryId, @Nonnull Map<String, String> values) {
        Preview preview = id == null || id.isBlank() ? null : PREVIEWS.get(key(id));
        if (preview == null) {
            return null;
        }
        try {
            return preview.line(entryId, Map.copyOf(values));
        } catch (Throwable t) {
            SafeLog.warn("[owner] the preview '" + id + "' an owner form shows could not run: " + t.getMessage());
            return null;
        }
    }

    /** Forget every preview. Tests only. */
    public static void clearForTests() {
        PREVIEWS.clear();
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
