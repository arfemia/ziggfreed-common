package com.ziggfreed.common.ui.form;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * What an owner form shows right now, read once per open: for each setting, the value the owner's own file writes
 * (if it writes one) and the value under the owner's word (the file's binding's Default), each as the text its
 * control shows; and the section headings and notes between them, in order. Pure apart from reading the owner file.
 *
 * <p>The owner file is read as JSON: absent or blank is an empty file, and one that cannot be read is
 * {@link #malformed()}, which the page shows and never writes (the file's writer refuses it too). An id-keyed file's
 * entry is found without regard to case and written under the key the file already spells, so a write never adds a
 * second entry that differs only in case.
 *
 * <p>One entry of a form of the Entries shape ({@link #read(OwnerFormAsset, String)}) reads the same way, its
 * settings' write paths inside the entry ({@link #entryId()} names it), for the store's own entry writer.
 */
public final class OwnerFormPlan {

    /** One line of the form, in order: a section's heading, its note, or a setting. */
    public record Line(@Nullable String heading, @Nullable String note, @Nullable Setting setting) {
    }

    /**
     * One setting: the writer's view of it, the authored field (its words), the text the layer under the owner gives
     * its control, and the owner's own text when the file sets it.
     *
     * @param ownerRaw the owner file's value as control text, or null when the file does not set it
     * @param below    the value under the owner's word as read (for the Default line), or null when none is known
     */
    public record Setting(@Nonnull OwnerFormField field, @Nonnull OwnerFormAsset.Field authored,
            @Nullable Object below, @Nullable String ownerRaw) {

        /** Does the owner's file set it? */
        public boolean set() {
            return ownerRaw != null;
        }

        /** The text its control shows: the owner's value, else the one under it, else blank. */
        @Nonnull
        public String shown() {
            return ownerRaw != null ? ownerRaw : raw(field.kind(), below);
        }
    }

    @Nonnull private final OwnerFormAsset form;
    @Nullable private final OwnerFileBinding binding;
    private final boolean malformed;
    @Nonnull private final List<Line> lines;
    @Nonnull private final List<Setting> settings;
    @Nullable private final String entryId;

    private OwnerFormPlan(@Nonnull OwnerFormAsset form, @Nullable OwnerFileBinding binding, boolean malformed,
            @Nonnull List<Line> lines, @Nonnull List<Setting> settings, @Nullable String entryId) {
        this.form = form;
        this.binding = binding;
        this.malformed = malformed;
        this.lines = List.copyOf(lines);
        this.settings = List.copyOf(settings);
        this.entryId = entryId;
    }

    /** Read {@code form} against its file's binding and the file as it stands. */
    @Nonnull
    public static OwnerFormPlan read(@Nonnull OwnerFormAsset form) {
        return read(form, null);
    }

    /**
     * Read one entry of {@code form}, a form of the Entries shape: {@code entryId} (an id the form lists, or one an
     * owner just added) in place of the form's own {@code Entry}, each setting's write path inside the entry, so the
     * page writes it through the store's entry writer. Null reads {@code form} as {@link #read(OwnerFormAsset)} does.
     */
    @Nonnull
    public static OwnerFormPlan read(@Nonnull OwnerFormAsset form, @Nullable String entryId) {
        OwnerFileBinding binding = OwnerFileBindings.find(form.file());
        JsonObject root = new JsonObject();
        boolean malformed = false;
        if (binding != null) {
            Path file = binding.file();
            try {
                root = readObject(file);
            } catch (Exception e) {
                malformed = true;
                SafeLog.warn("[owner] " + file + " could not be read for the owner form '" + form.getId() + "': "
                        + e.getMessage());
            }
        }
        if (!OwnerFiles.schemaReadable(root, "owner", form.getId())) {
            malformed = true;
        }
        boolean insideEntry = entryId != null;
        String entry = insideEntry ? entryId : form.entry();
        String entryKey = entry == null ? null : keyIn(root, entry);
        JsonObject scope = entryKey == null ? root : objectAt(root, entryKey);
        List<Line> lines = new ArrayList<>();
        List<Setting> settings = new ArrayList<>();
        for (OwnerFormAsset.Section section : form.sections()) {
            if (section.heading() != null) {
                lines.add(new Line(section.heading(), null, null));
            }
            if (section.note() != null) {
                lines.add(new Line(null, section.note(), null));
            }
            for (OwnerFormAsset.Field field : section.fields()) {
                OwnerFormKind kind = field.kind();
                String path = field.path();
                if (kind == null || !OwnerFormAsset.isPath(path) || (entry != null && !OwnerFormAsset.isEntry(entry))) {
                    continue;
                }
                String writePath = insideEntry || entryKey == null ? path : entryKey + "." + path;
                List<String> choices = new ArrayList<>();
                for (OwnerFormAsset.Choice choice : field.choices()) {
                    choices.add(choice.value());
                }
                OwnerFormField writer = new OwnerFormField(settings.size(), kind, writePath, field.min(), field.max(),
                        choices);
                Object below = binding == null ? null : binding.below(entry, path);
                String owner = scope == null ? null : ownerRaw(kind, leaf(scope, path));
                Setting setting = new Setting(writer, field, below, owner);
                settings.add(setting);
                lines.add(new Line(null, null, setting));
            }
        }
        return new OwnerFormPlan(form, binding, malformed, lines, settings, entryId);
    }

    @Nonnull
    public OwnerFormAsset form() {
        return form;
    }

    /**
     * The entry this plan edits through the store's entry writer (its settings' write paths inside it), or null for a
     * form of settings, whose write paths start at the file's root.
     */
    @Nullable
    public String entryId() {
        return entryId;
    }

    /** The file's binding, or null when no installed mod registered the file the form names. */
    @Nullable
    public OwnerFileBinding binding() {
        return binding;
    }

    /** Could the owner file not be read? Then the form shows the defaults and writes nothing. */
    public boolean malformed() {
        return malformed;
    }

    /** May the form write: a bound file that reads (and, for one entry, a file keyed by id). */
    public boolean writable() {
        return binding != null && !malformed && (entryId == null || binding.keyed());
    }

    /** Every line, in order. */
    @Nonnull
    public List<Line> lines() {
        return lines;
    }

    /** Every setting, in order; a setting's {@code field().index()} is its place here. */
    @Nonnull
    public List<Setting> settings() {
        return settings;
    }

    /** The setting at {@code index}, or null. */
    @Nullable
    public Setting setting(int index) {
        return index >= 0 && index < settings.size() ? settings.get(index) : null;
    }

    /** Every setting's writer view, in order. */
    @Nonnull
    public List<OwnerFormField> fields() {
        List<OwnerFormField> out = new ArrayList<>(settings.size());
        for (Setting setting : settings) {
            out.add(setting.field());
        }
        return out;
    }

    /**
     * A value as the text a control of {@code kind} shows: a switch's {@code on} or {@code off}, a list joined by
     * commas, a number as the file would spell it ({@code 200}, {@code 1.5}), anything else as written; blank for none.
     */
    @Nonnull
    public static String raw(@Nonnull OwnerFormKind kind, @Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (kind == OwnerFormKind.SWITCH) {
            String word = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            return "true".equals(word) || "on".equals(word) ? "on" : "false".equals(word) || "off".equals(word)
                    ? "off" : "";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    parts.add(String.valueOf(item));
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() : "";
        }
        return String.valueOf(value);
    }

    /** The owner file's leaf as control text, or null when the file does not set it. */
    @Nullable
    private static String ownerRaw(@Nonnull OwnerFormKind kind, @Nullable JsonElement leaf) {
        if (leaf == null || leaf.isJsonNull() || leaf.isJsonObject()) {
            return null;
        }
        if (leaf.isJsonArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonElement item : (JsonArray) leaf) {
                if (item != null && item.isJsonPrimitive()) {
                    items.add(item.getAsString());
                }
            }
            return raw(kind, items);
        }
        JsonPrimitive primitive = leaf.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return raw(kind, primitive.getAsBoolean());
        }
        return primitive.getAsString();
    }

    /** The file's root object: empty when absent or blank; throws when it is not a JSON object. */
    @Nonnull
    static JsonObject readObject(@Nonnull Path file) throws Exception {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("its root is not a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    /**
     * The key of {@code root} that names {@code entry} without regard to case (a key a form may write under), else
     * {@code entry} as written.
     */
    @Nonnull
    static String keyIn(@Nonnull JsonObject root, @Nonnull String entry) {
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String key = e.getKey();
            if (!OwnerFiles.isReservedKey(key) && OwnerFormAsset.isEntry(key) && key.equalsIgnoreCase(entry)) {
                return key;
            }
        }
        return entry;
    }

    @Nullable
    static JsonObject objectAt(@Nonnull JsonObject root, @Nonnull String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    static JsonElement leaf(@Nonnull JsonObject scope, @Nonnull String path) {
        JsonElement cursor = scope;
        for (String part : path.split("\\.")) {
            if (cursor == null || !cursor.isJsonObject()) {
                return null;
            }
            cursor = cursor.getAsJsonObject().get(part);
        }
        return cursor;
    }
}
