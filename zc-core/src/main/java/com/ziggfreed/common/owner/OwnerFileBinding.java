package com.ziggfreed.common.owner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.JsonOverrideWriter;
import com.ziggfreed.common.util.SafeLog;

/**
 * One owner file an in-game editor may write, as the mod that reads it registers it ({@link OwnerFileBindings}): the
 * file's id (what an owner form's {@code File} names), where it lives, the value under the owner's own word for a leaf
 * (the Default an editor shows), how a change is written, and how the mod reads the file again after one.
 *
 * <p>The binding owns the path: an editor never builds one from a form's text or a player's, so a form can only
 * write a file some installed mod registered. A file the library reads as a keyed store needs no code past one
 * {@link #keyedStore} line (its Default is the store's {@code resolveBelowOwner}, encoded through the store's own
 * codec); a consumer's own file type passes its own {@link Below} and {@link Writer} (its camelCase files, a version
 * backup) through the {@link #builder}.
 *
 * <p>The default writer is zc-core's {@link JsonOverrideWriter#setLeaves}, which never overwrites a file it cannot
 * read and keeps every sibling key; the caller owns number types ({@code Integer} for an integer leaf, {@code Double}
 * for a double one). Every call here is guarded: a throwing reader answers no Default, a throwing writer writes
 * nothing, a throwing reload is one warning.
 *
 * <p>An id-keyed file a keyed store reads also carries its {@link Entries} (every {@link #keyedStore} binding does):
 * the ids in force, which of them a layer under the owner holds, each entry as it reads now, and the store's own entry
 * writer, which holds a typed id to the store's id pattern before it writes. An owner form of the Entries shape edits
 * the file one entry at a time through it.
 *
 * <p>A file whose shape a form cannot address leaf by leaf (a list a leaf sits in, a map that stands whole) names a
 * {@link View}: the owner's file as an editor reads it, so the leaves the form names are found where the owner wrote
 * them, and its {@link Entries} writer turns those leaves back into the file's own shape. Unnamed, an editor reads the
 * file as written.
 */
public final class OwnerFileBinding {

    /**
     * One id-keyed owner file's entries, as the keyed store that reads it answers them
     * ({@link #entriesOf(AbstractKeyedAssetConfig, Codec)}).
     */
    public interface Entries {

        /** Every id in force, as each entry spells it, in no particular order. */
        @Nonnull
        List<String> ids();

        /** Does a layer under the owner's file (a pack's file, a default) hold {@code id}? */
        boolean hasBelow(@Nonnull String id);

        /** The entry {@code id} names as it reads now, through the store's own codec; null when there is none. */
        @Nullable
        BsonDocument resolved(@Nonnull String id);

        /** May an owner name {@code id} for an entry (the store's id pattern)? */
        boolean accepts(@Nullable String id);

        /** Write {@code leaves} (dotted paths inside the entry) into {@code file}'s entry for {@code id}. */
        boolean put(@Nonnull Path file, @Nonnull String id, @Nonnull Map<String, Object> leaves);

        /** Take {@code file}'s whole entry for {@code id} out. */
        boolean remove(@Nonnull Path file, @Nonnull String id);
    }

    /** The value under the owner's word for one leaf of the file: a Boolean, a Number, a String or a List, else null. */
    @FunctionalInterface
    public interface Below {

        /**
         * @param entry the id-keyed entry the leaf sits under ({@code Default}), or null for a file of plain leaves
         * @param path  the dotted PascalCase path inside the entry ({@code Limits.Personal})
         */
        @Nullable
        Object read(@Nullable String entry, @Nonnull String path);
    }

    /** Writes leaves into the file: dotted paths from the file's root, a null value removing its leaf. */
    @FunctionalInterface
    public interface Writer {

        boolean write(@Nonnull Path file, @Nonnull Map<String, Object> leaves);
    }

    /**
     * The owner's file as an editor reads it: the file's root object, read as written, answered in the shape the
     * editor's paths address (a leaf the file keeps inside a list, lifted to a plain path), never written back.
     */
    @FunctionalInterface
    public interface View {

        /** {@code root}, as an editor reads it; may change and answer {@code root} itself, which is a fresh copy. */
        @Nonnull
        JsonObject view(@Nonnull JsonObject root);
    }

    /** No Default to show. */
    public static final Below NO_BELOW = (entry, path) -> null;

    @Nonnull private final String id;
    @Nonnull private final Supplier<Path> file;
    @Nonnull private final Below below;
    @Nonnull private final Writer writer;
    @Nonnull private final Runnable reload;
    @Nullable private final Entries entries;
    @Nullable private final View view;

    private OwnerFileBinding(@Nonnull Builder builder) {
        this.id = builder.id;
        this.file = builder.file;
        this.below = builder.below;
        this.writer = builder.writer;
        this.reload = builder.reload;
        this.entries = builder.entries;
        this.view = builder.view;
    }

    /**
     * A binding for {@code id} (the file's path under {@code mods/}, by convention, {@code yourmod/your-file.json}),
     * written where {@code file} says, through {@link JsonOverrideWriter} unless the builder names another writer.
     */
    @Nonnull
    public static Builder builder(@Nonnull String id, @Nonnull Supplier<Path> file) {
        return new Builder(id, file);
    }

    /**
     * The binding of an id-keyed owner file the library folds as a keyed store: its Default is the store's answer
     * under the owner's layer for the entry ({@link AbstractKeyedAssetConfig#resolveBelowOwner}), encoded through the
     * store's own codec, so a leaf reads in the codec's own number type; it is written through
     * {@link JsonOverrideWriter} and read again through {@code reload}.
     */
    @Nonnull
    public static <T> OwnerFileBinding keyedStore(@Nonnull String id, @Nonnull Supplier<Path> file,
            @Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull Codec<T> codec, @Nonnull Runnable reload) {
        return builder(id, file).below(belowFrom(config, codec)).entries(entriesOf(config, codec)).reload(reload)
                .build();
    }

    /**
     * A keyed store's {@link Entries}: its ids in force ({@link AbstractKeyedAssetConfig#all}, each spelled as its
     * asset's own id where it has one), the layer under the owner ({@code resolveBelowOwner}), each entry encoded through
     * {@code codec}, and the store's own entry writer ({@code acceptsOwnerEntryId}, {@code putOwnerLeaves},
     * {@code removeOwnerEntry}).
     */
    @Nonnull
    public static <T> Entries entriesOf(@Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull Codec<T> codec) {
        return new Entries() {
            @Override
            @Nonnull
            public List<String> ids() {
                List<String> out = new ArrayList<>();
                for (Map.Entry<String, T> entry : config.all().entrySet()) {
                    T value = entry.getValue();
                    Object own = value instanceof JsonAsset<?> asset ? asset.getId() : null;
                    out.add(own instanceof String spelled && !spelled.isBlank() ? spelled : entry.getKey());
                }
                return out;
            }

            @Override
            public boolean hasBelow(@Nonnull String id) {
                return config.resolveBelowOwner(id.trim()) != null;
            }

            @Override
            @Nullable
            public BsonDocument resolved(@Nonnull String id) {
                T value = config.resolve(id.trim());
                if (value == null) {
                    return null;
                }
                BsonValue encoded = codec.encode(value, new ExtraInfo());
                return encoded != null && encoded.isDocument() ? encoded.asDocument() : null;
            }

            @Override
            public boolean accepts(@Nullable String id) {
                return config.acceptsOwnerEntryId(id);
            }

            @Override
            public boolean put(@Nonnull Path file, @Nonnull String id, @Nonnull Map<String, Object> leaves) {
                return config.putOwnerLeaves(file, id, leaves);
            }

            @Override
            public boolean remove(@Nonnull Path file, @Nonnull String id) {
                return config.removeOwnerEntry(file, id);
            }
        };
    }

    /** A keyed store's Default reader ({@link #keyedStore}), for a builder that also names its own writer. */
    @Nonnull
    public static <T> Below belowFrom(@Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull Codec<T> codec) {
        return (entry, path) -> {
            if (entry == null || entry.isBlank()) {
                return null;
            }
            T value = config.resolveBelowOwner(entry.trim());
            if (value == null) {
                return null;
            }
            BsonValue encoded = codec.encode(value, new ExtraInfo());
            return encoded != null && encoded.isDocument() ? leaf(encoded.asDocument(), path) : null;
        };
    }

    /** The file's id: what an owner form's {@code File} names. */
    @Nonnull
    public String id() {
        return id;
    }

    /** Where the file lives right now. */
    @Nonnull
    public Path file() {
        return file.get();
    }

    /** The Default for one leaf, or null when the layer under the owner holds none (or the reader threw). */
    @Nullable
    public Object below(@Nullable String entry, @Nonnull String path) {
        try {
            return below.read(entry, path);
        } catch (Throwable t) {
            SafeLog.warn("[owner] the default of " + path + " in " + id + " could not be read: " + t.getMessage());
            return null;
        }
    }

    /** Write {@code leaves} into the file; false when nothing was written (the file left as it was). */
    public boolean write(@Nonnull Map<String, Object> leaves) {
        try {
            return writer.write(file(), leaves);
        } catch (Throwable t) {
            SafeLog.warn("[owner] " + id + " could not be written: " + t.getMessage());
            return false;
        }
    }

    /** Have the mod that reads the file read it again. */
    public void reload() {
        try {
            reload.run();
        } catch (Throwable t) {
            SafeLog.warn("[owner] " + id + " was written but could not be read again: " + t.getMessage());
        }
    }

    /**
     * The owner's file {@code root} (read as written) as an editor reads it, through the binding's {@link View}; the
     * root itself when the binding names none, or when the view throws (one warning), so an editor still shows what
     * the file plainly says.
     */
    @Nonnull
    public JsonObject ownerView(@Nonnull JsonObject root) {
        if (view == null) {
            return root;
        }
        try {
            JsonObject seen = view.view(root.deepCopy());
            return seen != null ? seen : root;
        } catch (Throwable t) {
            SafeLog.warn("[owner] " + id + " could not be read the way its editor reads it: " + t.getMessage());
            return root;
        }
    }

    /**
     * Does an editor read this file through a {@link View}? Then a write of one leaf may move others (a list or a map
     * written whole), so an editor reads the file again after each write rather than repainting the one leaf.
     */
    public boolean reshapes() {
        return view != null;
    }

    /** Is the file id-keyed, with {@link Entries} an editor may list and write one at a time? */
    public boolean keyed() {
        return entries != null;
    }

    /** Every id in force, as each entry spells it; empty for a file that is not id-keyed (or a reader that threw). */
    @Nonnull
    public List<String> entryIds() {
        if (entries == null) {
            return List.of();
        }
        try {
            return List.copyOf(entries.ids());
        } catch (Throwable t) {
            SafeLog.warn("[owner] the entries of " + id + " could not be listed: " + t.getMessage());
            return List.of();
        }
    }

    /** Does a layer under the owner's file hold {@code entryId}? True when it cannot tell, so nothing is removed. */
    public boolean entryHasBelow(@Nonnull String entryId) {
        if (entries == null) {
            return true;
        }
        try {
            return entries.hasBelow(entryId);
        } catch (Throwable t) {
            SafeLog.warn("[owner] whether " + entryId + " in " + id + " ships in a pack could not be read: "
                    + t.getMessage());
            return true;
        }
    }

    /** The entry {@code entryId} names as it reads now, or null (none, not id-keyed, or the reader threw). */
    @Nullable
    public BsonDocument entry(@Nonnull String entryId) {
        if (entries == null) {
            return null;
        }
        try {
            return entries.resolved(entryId);
        } catch (Throwable t) {
            SafeLog.warn("[owner] " + entryId + " in " + id + " could not be read: " + t.getMessage());
            return null;
        }
    }

    /** May an owner name {@code entryId} for an entry of the file (the store's id pattern)? False when not id-keyed. */
    public boolean acceptsEntryId(@Nullable String entryId) {
        if (entries == null) {
            return false;
        }
        try {
            return entries.accepts(entryId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Write {@code leaves} (dotted paths inside one entry) into the file's entry for {@code entryId} through the
     * store's entry writer; false, nothing written, when the file is not id-keyed, the store refuses the id, or the
     * write failed. The caller reads the file back ({@link #reload}).
     */
    public boolean putEntry(@Nonnull String entryId, @Nonnull Map<String, Object> leaves) {
        if (entries == null || !acceptsEntryId(entryId)) {
            return false;
        }
        try {
            return entries.put(file(), entryId, leaves);
        } catch (Throwable t) {
            SafeLog.warn("[owner] " + entryId + " in " + id + " could not be written: " + t.getMessage());
            return false;
        }
    }

    /**
     * Take the file's whole entry for {@code entryId} out through the store's entry writer; false when the file is not
     * id-keyed, the store refuses the id, or the write failed. The caller reads the file back.
     */
    public boolean removeEntry(@Nonnull String entryId) {
        if (entries == null || !acceptsEntryId(entryId)) {
            return false;
        }
        try {
            return entries.remove(file(), entryId);
        } catch (Throwable t) {
            SafeLog.warn("[owner] " + entryId + " in " + id + " could not be taken out: " + t.getMessage());
            return false;
        }
    }

    /** The leaf at a dotted path inside {@code document}, as a plain Java value; null when absent or not a leaf. */
    @Nullable
    static Object leaf(@Nonnull BsonDocument document, @Nonnull String path) {
        BsonValue cursor = document;
        for (String part : path.split("\\.")) {
            if (cursor == null || !cursor.isDocument()) {
                return null;
            }
            cursor = cursor.asDocument().get(part);
        }
        return javaValue(cursor);
    }

    /**
     * A BSON leaf as the plain value an editor shows and a writer takes back: a Boolean, an Integer, a Long, a
     * Double, a String or a List of those; null for a document, a null or anything else.
     */
    @Nullable
    static Object javaValue(@Nullable BsonValue value) {
        if (value == null) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean().getValue();
        }
        if (value.isInt32()) {
            return value.asInt32().getValue();
        }
        if (value.isInt64()) {
            return value.asInt64().getValue();
        }
        if (value.isDouble()) {
            return value.asDouble().getValue();
        }
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isArray()) {
            List<Object> out = new ArrayList<>();
            for (BsonValue item : value.asArray()) {
                Object plain = javaValue(item);
                if (plain != null) {
                    out.add(plain);
                }
            }
            return out;
        }
        return null;
    }

    /** Immutable-by-copy assembly. */
    public static final class Builder {

        @Nonnull private final String id;
        @Nonnull private final Supplier<Path> file;
        @Nonnull private Below below = NO_BELOW;
        @Nonnull private Writer writer = JsonOverrideWriter::setLeaves;
        @Nonnull private Runnable reload = () -> { };
        @Nullable private Entries entries;
        @Nullable private View view;

        private Builder(@Nonnull String id, @Nonnull Supplier<Path> file) {
            this.id = Objects.requireNonNull(id, "id").trim();
            this.file = Objects.requireNonNull(file, "file");
            if (this.id.isEmpty()) {
                throw new IllegalArgumentException("an owner file binding needs an id");
            }
        }

        /** What the layer under the owner holds for a leaf; unset, no Default is shown. */
        @Nonnull
        public Builder below(@Nullable Below value) {
            this.below = value != null ? value : NO_BELOW;
            return this;
        }

        /** How a change is written; unset, {@link JsonOverrideWriter#setLeaves}. */
        @Nonnull
        public Builder writer(@Nullable Writer value) {
            this.writer = value != null ? value : JsonOverrideWriter::setLeaves;
            return this;
        }

        /** How the mod reads the file again after a write; unset, nothing. */
        @Nonnull
        public Builder reload(@Nullable Runnable value) {
            this.reload = value != null ? value : () -> { };
            return this;
        }

        /** The file's entries, for an id-keyed file an editor may write one entry at a time; unset, none. */
        @Nonnull
        public Builder entries(@Nullable Entries value) {
            this.entries = value;
            return this;
        }

        /** How an editor reads the owner's file, for a shape its paths cannot address as written; unset, as written. */
        @Nonnull
        public Builder view(@Nullable View value) {
            this.view = value;
            return this;
        }

        @Nonnull
        public OwnerFileBinding build() {
            return new OwnerFileBinding(this);
        }
    }
}
