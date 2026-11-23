package com.ziggfreed.common.ui.form;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;

import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * What an owner form of the Entries shape ({@link OwnerFormAsset#entries()}) lists right now, read once per open: a
 * row for each entry of its id-keyed file it shows, in id order, each saying whether the owner's file sets any of the
 * form's settings for it, whether a pack ships it and whether the owner's file holds it at all. Pure apart from
 * reading the owner file and the store, through the file's binding ({@link OwnerFileBinding#keyed()}).
 *
 * <p><b>Which entries.</b> Every entry in force, less those that do not hold the group's {@code Having} path, less
 * those whose {@code Gate} block asks for a feature switched off on this server or a mod that is not installed: the
 * plain top-level conditions the library's hide axis lifts ({@link FeatureLift#liftKnown}), read live, so an entry is
 * hidden exactly where the game hides that content from players. A block that cannot be read hides its entry. An id
 * a form may not write under ({@link OwnerFormAsset#isEntry}) is never listed.
 */
public final class OwnerFormEntries {

    /**
     * One entry the form lists.
     *
     * @param id      the entry's id, as the entry spells it
     * @param nameKey the localization key naming it (the group's {@code Name} path), or null to show its id
     * @param set     does the owner's file set one of the form's settings for it?
     * @param below   does a layer under the owner's file (a pack) hold it?
     * @param owned   does the owner's file hold an entry for it at all?
     */
    public record Row(@Nonnull String id, @Nullable String nameKey, boolean set, boolean below, boolean owned) {

        /** Does the owner's file alone hold it, with no pack shipping it (an entry the owner added)? */
        public boolean added() {
            return owned && !below;
        }
    }

    @Nonnull private final OwnerFormAsset form;
    @Nullable private final OwnerFileBinding binding;
    private final boolean malformed;
    @Nonnull private final List<Row> rows;

    private OwnerFormEntries(@Nonnull OwnerFormAsset form, @Nullable OwnerFileBinding binding, boolean malformed,
            @Nonnull List<Row> rows) {
        this.form = form;
        this.binding = binding;
        this.malformed = malformed;
        this.rows = List.copyOf(rows);
    }

    /** Read what {@code form} lists against its file's binding and the owner's file as it stands. */
    @Nonnull
    public static OwnerFormEntries read(@Nonnull OwnerFormAsset form) {
        OwnerFileBinding binding = OwnerFileBindings.find(form.file());
        OwnerFormAsset.Entries spec = form.entries();
        JsonObject root = new JsonObject();
        boolean malformed = false;
        if (binding != null) {
            Path file = binding.file();
            try {
                root = OwnerFormPlan.readObject(file);
            } catch (Exception e) {
                malformed = true;
                SafeLog.warn("[owner] " + file + " could not be read for the owner form '" + form.getId() + "': "
                        + e.getMessage());
            }
        }
        if (!OwnerFiles.schemaReadable(root, "owner", form.getId())) {
            malformed = true;
        }
        if (binding != null && !malformed) {
            root = binding.ownerView(root);
        }
        List<Row> rows = new ArrayList<>();
        if (binding != null && binding.keyed() && spec != null) {
            List<String> paths = declaredPaths(form);
            List<String> ids = new ArrayList<>(binding.entryIds());
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            for (String id : ids) {
                if (!OwnerFormAsset.isEntry(id)) {
                    continue;
                }
                BsonDocument entry = binding.entry(id);
                if (entry == null || !shows(spec, entry)) {
                    continue;
                }
                JsonObject owner = OwnerFormPlan.objectAt(root, OwnerFormPlan.keyIn(root, id));
                boolean set = false;
                if (owner != null) {
                    for (String path : paths) {
                        JsonElement leaf = OwnerFormPlan.leaf(owner, path);
                        if (leaf != null && !leaf.isJsonNull()) {
                            set = true;
                            break;
                        }
                    }
                }
                rows.add(new Row(id, nameKey(spec, entry), set, binding.entryHasBelow(id), owner != null));
            }
            rows.sort(Comparator.comparing(Row::id, String.CASE_INSENSITIVE_ORDER));
        }
        return new OwnerFormEntries(form, binding, malformed, rows);
    }

    /**
     * Does {@code form} list the entry {@code id} right now: a form of the Entries shape over a keyed file, an id a
     * form may write under, an entry in force that the form shows? Reads only that entry, for a check on every event.
     */
    public static boolean lists(@Nonnull OwnerFormAsset form, @Nullable String id) {
        OwnerFormAsset.Entries spec = form.entries();
        OwnerFileBinding binding = OwnerFileBindings.find(form.file());
        if (spec == null || binding == null || !binding.keyed() || !OwnerFormAsset.isEntry(id)) {
            return false;
        }
        BsonDocument entry = binding.entry(id);
        return entry != null && shows(spec, entry);
    }

    /**
     * Is {@code id} taken for a new entry: an entry in force has it, or the owner's file already writes an entry under
     * it (one the store keeps out, a mod's gate), any casing? True when the file cannot be read, so nothing is added.
     */
    public static boolean taken(@Nonnull OwnerFileBinding binding, @Nonnull String id) {
        for (String existing : binding.entryIds()) {
            if (existing.equalsIgnoreCase(id)) {
                return true;
            }
        }
        try {
            JsonObject root = OwnerFormPlan.readObject(binding.file());
            return !OwnerFormPlan.keyIn(root, id).equals(id) || root.has(id);
        } catch (Exception unreadable) {
            return true;
        }
    }

    /** The key naming the entry {@code id} (the group's {@code Name} path), or null to show its id. */
    @Nullable
    public static String nameKeyOf(@Nonnull OwnerFormAsset form, @Nonnull String id) {
        OwnerFormAsset.Entries spec = form.entries();
        OwnerFileBinding binding = OwnerFileBindings.find(form.file());
        BsonDocument entry = spec == null || binding == null ? null : binding.entry(id);
        return entry == null ? null : nameKey(spec, entry);
    }

    @Nonnull
    public OwnerFormAsset form() {
        return form;
    }

    /** The file's binding, or null when no installed mod registered the file the form names. */
    @Nullable
    public OwnerFileBinding binding() {
        return binding;
    }

    /** Is the file keyed by id, so its entries can be listed and written one at a time? */
    public boolean keyed() {
        return binding != null && binding.keyed();
    }

    /** Could the owner's file not be read? Then the form lists what it can and writes nothing. */
    public boolean malformed() {
        return malformed;
    }

    /** May the form write: a bound, id-keyed file that reads. */
    public boolean writable() {
        return keyed() && !malformed;
    }

    /** May an owner add an entry here: the group allows it and the file can be written. */
    public boolean addable() {
        OwnerFormAsset.Entries spec = form.entries();
        return spec != null && spec.add() && writable();
    }

    /** May an owner remove {@code row}: the group allows it, only the owner's file holds it, the file can be written. */
    public boolean removable(@Nonnull Row row) {
        OwnerFormAsset.Entries spec = form.entries();
        return spec != null && spec.remove() && row.added() && writable();
    }

    /** May an owner reset {@code row} (take the form's settings for it out of their file)? */
    public boolean resettable(@Nonnull Row row) {
        return row.set() && !removable(row) && writable();
    }

    /** Is there anything for an owner to do here: an entry listed, or one they may add? */
    public boolean offersAnything() {
        return !rows.isEmpty() || addable();
    }

    /** Every listed entry, in id order; a row's place here is the index the page's events name it by. */
    @Nonnull
    public List<Row> rows() {
        return rows;
    }

    /** The row at {@code index}, or null. */
    @Nullable
    public Row row(int index) {
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    /**
     * The leaves that take every one of the form's settings for an entry out of the owner's file (dotted paths inside
     * the entry, each null), so the entry reads as the packs say again; nothing else the owner wrote there moves.
     */
    @Nonnull
    public static Map<String, Object> resetLeaves(@Nonnull OwnerFormAsset form) {
        return resetLeaves(form, field -> true);
    }

    /**
     * {@link #resetLeaves(OwnerFormAsset)} for the settings {@code shown} lets through alone: a setting the owner may
     * not change ({@link OwnerForms#fieldShownTo}) is left as their file writes it.
     */
    @Nonnull
    public static Map<String, Object> resetLeaves(@Nonnull OwnerFormAsset form,
            @Nonnull Predicate<OwnerFormAsset.Field> shown) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        for (OwnerFormAsset.Section section : form.sections()) {
            for (OwnerFormAsset.Field field : section.fields()) {
                String path = field.path();
                if (field.kind() != null && OwnerFormAsset.isPath(path) && shown.test(field)) {
                    leaves.put(path, null);
                }
            }
        }
        return leaves;
    }

    /**
     * The leaves an entry an owner adds starts with: the group's {@code Having} path as an empty group, so the new
     * entry is listed, else nothing (an empty entry).
     */
    @Nonnull
    public static Map<String, Object> newEntryLeaves(@Nonnull OwnerFormAsset form) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        OwnerFormAsset.Entries spec = form.entries();
        String having = spec == null ? null : spec.having();
        if (having != null && OwnerFormAsset.isPath(having)) {
            leaves.put(having, new LinkedHashMap<String, Object>());
        }
        return leaves;
    }

    /**
     * Does {@code spec} show {@code entry}: it holds the {@code Having} path, and its {@code Gate} hides nothing? A
     * {@code Having} or {@code Gate} that is no path shows nothing (the audit names it), never everything.
     */
    static boolean shows(@Nonnull OwnerFormAsset.Entries spec, @Nonnull BsonDocument entry) {
        String having = spec.having();
        if (having != null && (!OwnerFormAsset.isPath(having) || at(entry, having) == null)) {
            return false;
        }
        String gate = spec.gate();
        if (gate == null) {
            return true;
        }
        return OwnerFormAsset.isPath(gate) && gateOpen(at(entry, gate));
    }

    /**
     * Is the Requires block {@code block} open on this server's hide axis: every plain top-level feature or
     * mod-presence condition it asks for is on ({@link FeatureLift#liftKnown}, {@link FeatureLift#allOn})? No block is
     * open; a block that cannot be read is shut, with one warning.
     */
    public static boolean gateOpen(@Nullable BsonValue block) {
        if (block == null || !block.isDocument()) {
            return true;
        }
        try {
            GateSpec gate = GateSpec.CODEC.decode(block, new ExtraInfo());
            return gate == null || FeatureLift.allOn(FeatureLift.liftKnown(gate).lifted());
        } catch (Throwable t) {
            SafeLog.warn("[owner] an entry's gate could not be read, so the owner form hides the entry: "
                    + t.getMessage());
            return false;
        }
    }

    /** The key at the group's {@code Name} path in {@code entry}, or null. */
    @Nullable
    private static String nameKey(@Nonnull OwnerFormAsset.Entries spec, @Nonnull BsonDocument entry) {
        String path = spec.name();
        BsonValue value = path == null ? null : at(entry, path);
        if (value == null || !value.isString()) {
            return null;
        }
        String key = value.asString().getValue();
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** The value at a dotted path inside {@code document}, or null when a part is missing or a null. */
    @Nullable
    static BsonValue at(@Nonnull BsonDocument document, @Nonnull String path) {
        BsonValue cursor = document;
        for (String part : path.split("\\.")) {
            if (cursor == null || !cursor.isDocument()) {
                return null;
            }
            cursor = cursor.asDocument().get(part);
        }
        return cursor == null || cursor.isNull() ? null : cursor;
    }

    /** Every path the form's settings write inside an entry, in order: each with a kind and a writable path. */
    @Nonnull
    private static List<String> declaredPaths(@Nonnull OwnerFormAsset form) {
        List<String> out = new ArrayList<>();
        for (OwnerFormAsset.Section section : form.sections()) {
            for (OwnerFormAsset.Field field : section.fields()) {
                String path = field.path();
                if (field.kind() != null && OwnerFormAsset.isPath(path) && !out.contains(path)) {
                    out.add(path);
                }
            }
        }
        return out;
    }
}
