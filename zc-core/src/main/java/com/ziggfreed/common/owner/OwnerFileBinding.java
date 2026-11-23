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
 */
public final class OwnerFileBinding {

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

    /** No Default to show. */
    public static final Below NO_BELOW = (entry, path) -> null;

    @Nonnull private final String id;
    @Nonnull private final Supplier<Path> file;
    @Nonnull private final Below below;
    @Nonnull private final Writer writer;
    @Nonnull private final Runnable reload;

    private OwnerFileBinding(@Nonnull Builder builder) {
        this.id = builder.id;
        this.file = builder.file;
        this.below = builder.below;
        this.writer = builder.writer;
        this.reload = builder.reload;
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
        return builder(id, file).below(belowFrom(config, codec)).reload(reload).build();
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

        @Nonnull
        public OwnerFileBinding build() {
            return new OwnerFileBinding(this);
        }
    }
}
