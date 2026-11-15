package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.content.ServerContent;

/**
 * One keyed group of objective rows in a document an editor writes, a quest's {@code Objectives} or an achievement's
 * {@code Criteria}: each row under the key a player's progress on it is saved under. Pure; each draft holds one,
 * built with its group, the {@code Meta} leaf its retired keys go in, a key never minted, and the leaves a successor
 * row keeps of the row it replaces.
 *
 * <p><b>Keys never move, and never come back.</b> A row's key is minted once from the words the admin typed
 * ({@link #mint}) and written under that key on every save after: a new count, target or line keeps it. A row whose
 * kind changes is a new row: {@link #keyFor} mints it a new key, which takes the old one's place ({@link Row#replaces}).
 * An emptied row is taken out by its key. Every key taken out is kept in the file's own {@code Meta} (the retired leaf
 * under {@link #META_NAMESPACE}), which the store carries untouched, and {@link #taken} hands it to every mint, so a
 * later row never lands on a player's old progress. A row that names no {@code Kind} is no row the editor shows: it
 * is kept as written.
 */
public final class KeyedRows {

    /** The library's namespace in a document's {@code Meta}. */
    public static final String META_NAMESPACE = "ziggfreedcommon";

    /**
     * One row: the key its progress is saved under (minted once, never renamed) and the leaves the editor writes.
     *
     * @param target    {@code Target}, or null for any
     * @param matchMode {@code MatchMode}, or null for the default
     * @param textKey   {@code TextKey}, or null for none
     * @param replaces  the key of the row this one takes the place of (the same slot, saved as another kind), which
     *                  is retired; null for none
     */
    public record Row(@Nonnull String key, @Nonnull String kind, @Nullable String target, long amount,
            @Nullable String matchMode, @Nullable String textKey, @Nullable String replaces) {
    }

    /**
     * What a document's group holds, as the editor shows it.
     *
     * @param rows    its first rows that name a {@code Kind}, up to the editor's slots
     * @param keys    every key the group holds, the ones past the slots and with no kind included
     * @param retired every key the document's {@code Meta} retires
     */
    public record Read(@Nonnull List<Row> rows, @Nonnull Set<String> keys, @Nonnull List<String> retired) {
    }

    @Nonnull private final String group;
    @Nonnull private final String retiredLeaf;
    @Nullable private final String reservedKey;
    @Nonnull private final List<String> placeLeaves;

    /**
     * @param group       the document's group the rows sit in ({@code Objectives}, {@code Criteria})
     * @param retiredLeaf the leaf under {@code Meta.ziggfreedcommon} listing every retired key
     * @param reservedKey a key no mint takes (a quest's report-back step), or null for none
     * @param placeLeaves the leaves a successor row keeps of the row it replaces: its place in the document
     */
    public KeyedRows(@Nonnull String group, @Nonnull String retiredLeaf, @Nullable String reservedKey,
            @Nonnull List<String> placeLeaves) {
        this.group = group;
        this.retiredLeaf = retiredLeaf;
        this.reservedKey = reservedKey;
        this.placeLeaves = List.copyOf(placeLeaves);
    }

    // ---------------------------------------------------------------- keys

    /**
     * A key from {@code typed} that {@code taken} does not hold (asked with the candidate lower-cased): the server
     * content pack's mint lower-cased ({@code "Kill Skeleton"} is {@code kill_skeleton}), else the first free
     * {@code _2}, {@code _3}. The reserved key is never minted.
     */
    @Nonnull
    public String mint(@Nullable String typed, @Nonnull Predicate<String> taken) {
        return ServerContent.mintId(typed, candidate -> taken.test(candidate.toLowerCase(Locale.ROOT))
                || (reservedKey != null && reservedKey.equalsIgnoreCase(candidate))).toLowerCase(Locale.ROOT);
    }

    /**
     * The key a slot saves under: its {@code savedKey} while the row is still of the kind it was saved as
     * ({@code savedKind}, matched without regard to case), so a new count, target or line keeps it; else a new key
     * minted from {@code typed}, never one {@code taken} holds. A changed kind is a new row, and the caller retires
     * the old key.
     */
    @Nonnull
    public String keyFor(@Nullable String savedKey, @Nullable String savedKind, @Nonnull String kind,
            @Nullable String typed, @Nonnull Predicate<String> taken) {
        if (savedKey != null && !savedKey.isBlank()
                && (savedKind == null || savedKind.isBlank() || savedKind.trim().equalsIgnoreCase(kind.trim()))) {
            return savedKey.trim();
        }
        return mint(typed, taken);
    }

    /** Every key a new row may not take: the group's keys and the retired ones, lower-cased. */
    @Nonnull
    public static Set<String> taken(@Nonnull Collection<String> keys, @Nonnull Collection<String> retired) {
        Set<String> taken = new HashSet<>();
        for (String key : keys) {
            taken.add(key.toLowerCase(Locale.ROOT));
        }
        for (String key : retired) {
            taken.add(key.toLowerCase(Locale.ROOT));
        }
        return taken;
    }

    // ---------------------------------------------------------------- writing

    /**
     * The rows, in the document's own order: each row the editor holds is written over its row by key, a row with a
     * new key in the place of the key it replaces (keeping the place leaves, {@link #placeOf}), an emptied one taken
     * out, one the editor has no slot for kept, and any other new key after the rest in slot order. Every key taken
     * out is retired in the document's {@code Meta}.
     */
    public void write(@Nonnull JsonObject doc, @Nonnull List<Row> rows, @Nonnull Collection<String> dropped) {
        JsonObject before = doc.has(group) && doc.get(group).isJsonObject() ? doc.getAsJsonObject(group)
                : new JsonObject();
        Map<String, Row> byKey = new LinkedHashMap<>();
        Map<String, Row> byReplaced = new LinkedHashMap<>();
        for (Row row : rows) {
            byKey.put(row.key(), row);
            if (row.replaces() != null) {
                byReplaced.put(row.replaces(), row);
            }
        }
        Set<String> droppedKeys = new HashSet<>(dropped);
        Set<String> retired = new LinkedHashSet<>();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : before.entrySet()) {
            Row row = byKey.remove(e.getKey());
            if (row != null) {
                out.add(e.getKey(), written(e.getValue(), row));
                continue;
            }
            Row successor = byReplaced.get(e.getKey());
            if (successor != null && byKey.remove(successor.key()) != null) {
                retired.add(e.getKey());
                out.add(successor.key(), written(placeOf(e.getValue()), successor));
            } else if (droppedKeys.contains(e.getKey()) || successor != null) {
                retired.add(e.getKey());
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        for (Row row : byKey.values()) {
            out.add(row.key(), written(null, row));
        }
        if (out.size() == 0) {
            doc.remove(group);
        } else {
            doc.add(group, out);
        }
        retire(doc, retired);
    }

    /** What a successor row keeps of the row it replaces: the place leaves, nothing else. */
    @Nullable
    private JsonObject placeOf(@Nonnull JsonElement replaced) {
        if (!replaced.isJsonObject()) {
            return null;
        }
        JsonObject kept = new JsonObject();
        for (String leaf : placeLeaves) {
            JsonElement value = replaced.getAsJsonObject().get(leaf);
            if (value != null) {
                kept.add(leaf, value.deepCopy());
            }
        }
        return kept;
    }

    /** Add {@code keys} to the document's retired keys, each once. */
    private void retire(@Nonnull JsonObject doc, @Nonnull Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Set<String> all = new LinkedHashSet<>(retired(doc));
        all.addAll(keys);
        JsonArray list = new JsonArray();
        for (String key : all) {
            list.add(key);
        }
        DraftJson.group(DraftJson.group(doc, "Meta"), META_NAMESPACE).add(retiredLeaf, list);
    }

    @Nonnull
    private static JsonObject written(@Nullable JsonElement before, @Nonnull Row row) {
        JsonObject out = before != null && before.isJsonObject() ? before.getAsJsonObject().deepCopy() : new JsonObject();
        out.addProperty("Kind", row.kind());
        DraftJson.setOrRemove(out, "Target", row.target());
        DraftJson.setOrRemove(out, "MatchMode", row.matchMode());
        out.addProperty("Amount", row.amount());
        DraftJson.setOrRemove(out, "TextKey", row.textKey());
        return out;
    }

    // ---------------------------------------------------------------- reading

    /** The keys the document's {@code Meta} retires, as written. */
    @Nonnull
    public List<String> retired(@Nonnull JsonObject doc) {
        JsonObject meta = DraftJson.object(doc, "Meta");
        JsonObject ours = meta == null ? null : DraftJson.object(meta, META_NAMESPACE);
        return DraftJson.strings(ours == null ? null : ours.get(retiredLeaf));
    }

    /**
     * Read the group as the editor shows it: its first {@code slots} rows that name a {@code Kind} (one that names
     * none is no row the editor shows, and the document keeps it as written), every key it holds, and the retired
     * ones.
     */
    @Nonnull
    public Read read(@Nonnull JsonObject doc, int slots) {
        List<Row> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        JsonObject held = DraftJson.object(doc, group);
        if (held != null) {
            for (Map.Entry<String, JsonElement> e : held.entrySet()) {
                keys.add(e.getKey());
                if (rows.size() >= slots || !e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject row = e.getValue().getAsJsonObject();
                String kind = DraftJson.text(row, "Kind");
                if (kind == null) {
                    continue;
                }
                Long amount = DraftJson.whole(row.get("Amount"));
                rows.add(new Row(e.getKey(), kind, DraftJson.text(row, "Target"), amount == null ? 1L : amount,
                        DraftJson.text(row, "MatchMode"), DraftJson.text(row, "TextKey"), null));
            }
        }
        return new Read(rows, keys, retired(doc));
    }
}
