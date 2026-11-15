package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The JSON reads and writes every editor's draft shares: a group made when the document holds none, a leaf set or
 * taken out, a scalar read as text. Pure.
 */
final class DraftJson {

    private DraftJson() {
    }

    /** {@code doc}'s group {@code key}, made (at the end) when it holds none. */
    @Nonnull
    static JsonObject group(@Nonnull JsonObject doc, @Nonnull String key) {
        JsonElement found = doc.get(key);
        if (found != null && found.isJsonObject()) {
            return found.getAsJsonObject();
        }
        JsonObject made = new JsonObject();
        doc.add(key, made);
        return made;
    }

    /** {@code doc}'s group {@code key}, or null when it holds none. */
    @Nullable
    static JsonObject object(@Nonnull JsonObject doc, @Nonnull String key) {
        JsonElement found = doc.get(key);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    /** Take group {@code key} out of {@code doc} when it holds nothing. */
    static void dropIfEmpty(@Nonnull JsonObject doc, @Nonnull String key) {
        JsonElement found = doc.get(key);
        if (found != null && found.isJsonObject() && found.getAsJsonObject().size() == 0) {
            doc.remove(key);
        }
    }

    /** Write {@code value} under {@code key}, or take the leaf out for a blank one. */
    static void setOrRemove(@Nonnull JsonObject group, @Nonnull String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            group.remove(key);
        } else {
            group.addProperty(key, value);
        }
    }

    /** True writes the switch; false takes it out, since an unwritten switch is off. */
    static void setFlag(@Nonnull JsonObject group, @Nonnull String key, boolean on) {
        if (on) {
            group.addProperty(key, true);
        } else {
            group.remove(key);
        }
    }

    /** Take out every leaf of {@code group} named {@code name}, in any case. */
    static void removeIgnoringCase(@Nonnull JsonObject group, @Nonnull String name) {
        List<String> found = new ArrayList<>();
        for (String key : group.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                found.add(key);
            }
        }
        for (String key : found) {
            group.remove(key);
        }
    }

    /** Leaf {@code key} as text, or null when it is missing, blank or no scalar. */
    @Nullable
    static String text(@Nonnull JsonObject group, @Nonnull String key) {
        String value = scalar(group.get(key));
        return value == null || value.isBlank() ? null : value;
    }

    @Nonnull
    static String nonNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** A string, number or true/false as text; null for anything else. */
    @Nullable
    static String scalar(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive().getAsString() : null;
    }

    static boolean isTrue(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    /** A whole number written as a number or as text, or null when it reads as none. */
    @Nullable
    static Long whole(@Nullable JsonElement value) {
        String text = scalar(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A number as written: whole when it is whole. */
    @Nonnull
    static JsonPrimitive number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? new JsonPrimitive((long) value)
                : new JsonPrimitive(value);
    }

    /** The non-blank strings of a list leaf, trimmed, in order; empty for anything else. */
    @Nonnull
    static List<String> strings(@Nullable JsonElement listed) {
        List<String> out = new ArrayList<>();
        if (listed != null && listed.isJsonArray()) {
            for (JsonElement entry : listed.getAsJsonArray()) {
                String value = scalar(entry);
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }
}
