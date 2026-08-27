package com.ziggfreed.common.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.schema.metadata.Metadata;

/**
 * Schema-only hints for the in-game Asset Editor, attached to a codec field with
 * {@code .metadata(...)}: the field's effective default, and the closed value set behind a
 * string leaf.
 *
 * <p><b>Why this exists.</b> Most of this family's codec leaves are nullable on purpose - null
 * means "inherit under Parent, then fall back to the documented default at the read site" - so
 * the codec's default INSTANCE carries no value and the exported schema declares no
 * {@code default}. The editor then renders the control's zero-state (an unchecked box for a
 * boolean whose unauthored meaning is true), which lies about the effective value. These hints
 * write the same schema facts the engine's own codecs emit ({@code BooleanCodec.toSchema} sets
 * {@code default}, {@code EnumCodec.toSchema} sets {@code enum} plus
 * {@code hytale.type: "Enum"} and per-value descriptions) without touching decode: null still
 * means inherit-then-default at every read site.
 *
 * <p><b>Schema export only, and dropdowns are never validation.</b> Nothing here changes what a
 * codec accepts. {@link #oneOf} belongs ONLY on a field whose vocabulary is closed by code (the
 * reader refuses or ignores anything else); a pack-extensible vocabulary stays a plain string,
 * because a dropdown that rejects a legal pack value is worse than a text box. Hand-written JSON
 * never passes through the editor, so the content validators stay the real backstop.
 *
 * <p>Each hint is a no-op on a schema of a different shape than it targets, mirroring the
 * engine's own {@code NoDefaultValue} metadata: attaching a default to a field whose codec
 * exports a union simply leaves that schema alone.
 */
public final class EditorSchema {

    private EditorSchema() {
    }

    /** Declare the effective unauthored value of a boolean leaf (e.g. an Enabled defaulting true). */
    @Nonnull
    public static Metadata defaultValue(boolean value) {
        return schema -> {
            if (schema instanceof BooleanSchema s) {
                s.setDefault(value);
            }
        };
    }

    /** Declare the effective unauthored value of an integer-shaped leaf (INTEGER and LONG codecs). */
    @Nonnull
    public static Metadata defaultValue(long value) {
        return schema -> {
            if (schema instanceof IntegerSchema s) {
                s.setDefault((int) value);
            } else if (schema instanceof NumberSchema s) {
                s.setDefault((double) value);
            }
        };
    }

    /** Declare the effective unauthored value of a floating-point leaf. */
    @Nonnull
    public static Metadata defaultValue(double value) {
        return schema -> {
            if (schema instanceof NumberSchema s) {
                s.setDefault(value);
            }
        };
    }

    /** Declare the effective unauthored value of a string leaf, in the casing the reader documents. */
    @Nonnull
    public static Metadata defaultValue(@Nonnull String value) {
        return schema -> {
            if (schema instanceof StringSchema s) {
                s.setDefault(value);
            }
        };
    }

    /**
     * Declare a string leaf's closed value set, so the editor offers a dropdown of exactly these
     * values instead of a free-text box. Use the canonical authored casing; a case-folding reader
     * still accepts what it always accepted. On a string-array leaf the set applies to each entry.
     */
    @Nonnull
    public static Metadata oneOf(@Nonnull String... values) {
        String[] snapshot = values.clone();
        return schema -> {
            StringSchema s = stringLeaf(schema);
            if (s != null) {
                s.setEnum(snapshot.clone());
                s.getHytale().setType("Enum");
            }
        };
    }

    /**
     * Declare a string leaf's closed value set with a one-line meaning per value, shown beside each
     * dropdown entry. Arguments alternate value, meaning, value, meaning, ...
     */
    @Nonnull
    public static Metadata oneOfDocumented(@Nonnull String... valueDocPairs) {
        if (valueDocPairs.length == 0 || valueDocPairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "oneOfDocumented takes alternating value, meaning pairs; got " + valueDocPairs.length
                            + " arguments");
        }
        String[] values = new String[valueDocPairs.length / 2];
        String[] docs = new String[valueDocPairs.length / 2];
        for (int i = 0; i < values.length; i++) {
            values[i] = valueDocPairs[i * 2];
            docs[i] = valueDocPairs[i * 2 + 1];
        }
        return schema -> {
            StringSchema s = stringLeaf(schema);
            if (s != null) {
                s.setEnum(values.clone());
                s.setMarkdownEnumDescriptions(docs.clone());
                s.getHytale().setType("Enum");
            }
        };
    }

    /**
     * The string schema an enum hint applies to: the schema itself, or a string-array leaf's
     * single item schema (the entries are what carry the closed set there).
     */
    @Nullable
    private static StringSchema stringLeaf(@Nonnull Schema schema) {
        if (schema instanceof StringSchema s) {
            return s;
        }
        if (schema instanceof ArraySchema array && array.getItems() instanceof StringSchema item) {
            return item;
        }
        return null;
    }
}
