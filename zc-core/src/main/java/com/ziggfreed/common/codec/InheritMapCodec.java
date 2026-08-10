package com.ziggfreed.common.codec;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.InheritCodec;
import com.hypixel.hytale.codec.WrappedCodec;
import com.hypixel.hytale.codec.exception.CodecException;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A string-keyed {@link Map} codec that participates in native asset {@code Parent}
 * inheritance with per-KEY merge, unlike the stock {@link com.hypixel.hytale.codec.codecs.map.MapCodec}
 * (which the engine whole-replaces on inherit because it is not an {@link InheritCodec}).
 *
 * <p>When a child asset overlays a parent via {@code Parent}, the engine's
 * {@code BuilderField.decodeAndInheritJson} routes a field whose child codec is an
 * {@code InheritCodec} into {@link #decodeAndInheritJson}; this codec then:
 * <ul>
 *   <li>seeds the result with every parent entry,</li>
 *   <li>for each key the child provides: if the value codec is itself an
 *       {@code InheritCodec} (e.g. a {@code BuilderCodec} sub-object) AND the parent
 *       had that key, the entry is DEEP-MERGED (child fields over parent fields);
 *       otherwise the child entry replaces that key wholesale,</li>
 *   <li>keys present only on the parent are retained.</li>
 * </ul>
 * This is the opt-in "merging field codec" the framework supports (vanilla {@code Tags}
 * is the precedent). It is the primitive the dialogue {@code Nodes} map uses so a child
 * dialogue can override/add a single node while inheriting the rest.
 *
 * <p><b>Authoring-comment keys are skipped.</b> Any key starting with {@code $} (e.g.
 * {@code "$Comment"}, {@code "$TODO"}) is treated as authoring metadata, not a map entry:
 * it never reaches the value codec and never lands in the decoded map, on either the JSON
 * or the BSON path. This matches the engine {@code BuilderCodec}'s own {@code $}-convention,
 * which reserves the same prefix for its {@code EntryType.IGNORE} entries, so an author can
 * document a keyed map inline exactly the way they document a structured object. The value
 * of a skipped key may be any JSON shape (a string, an object, an array); it is consumed
 * without being decoded.
 *
 * <p>Generic and mod-agnostic: reuse for any keyed-map field that should overlay by key
 * under native inheritance. The value codec should be an {@code InheritCodec} (typically a
 * {@code BuilderCodec}) to get per-entry deep-merge; a non-inheriting value codec still
 * works, entries just replace whole.
 */
public final class InheritMapCodec<V> implements Codec<Map<String, V>>, InheritCodec<Map<String, V>>, WrappedCodec<V> {

    private final Codec<V> valueCodec;
    private final Supplier<Map<String, V>> supplier;

    public InheritMapCodec(@Nonnull Codec<V> valueCodec) {
        this(valueCodec, LinkedHashMap::new);
    }

    public InheritMapCodec(@Nonnull Codec<V> valueCodec, @Nonnull Supplier<Map<String, V>> supplier) {
        this.valueCodec = valueCodec;
        this.supplier = supplier;
    }

    @Override
    public Codec<V> getChildCodec() {
        return valueCodec;
    }

    // ==================== plain decode (no parent) ====================

    @Override
    public Map<String, V> decode(@Nonnull BsonValue bsonValue, @Nonnull ExtraInfo extraInfo) {
        return decodeInto(bsonValue.asDocument(), supplier.get(), null, extraInfo);
    }

    @Override
    public Map<String, V> decodeJson(@Nonnull RawJsonReader reader, @Nonnull ExtraInfo extraInfo) throws IOException {
        return decodeJsonInto(reader, supplier.get(), null, extraInfo);
    }

    @Nonnull
    @Override
    public BsonValue encode(@Nonnull Map<String, V> map, ExtraInfo extraInfo) {
        BsonDocument document = new BsonDocument();
        for (Map.Entry<String, V> entry : map.entrySet()) {
            BsonValue value = valueCodec.encode(entry.getValue(), extraInfo);
            if (value != null && !value.isNull()
                    && (!value.isDocument() || !value.asDocument().isEmpty())
                    && (!value.isArray() || !value.asArray().isEmpty())) {
                document.put(entry.getKey(), value);
            }
        }
        return document;
    }

    // ==================== inherit (BSON) ====================

    @Nullable
    @Override
    public Map<String, V> decodeAndInherit(BsonDocument document, Map<String, V> parent, ExtraInfo extraInfo) {
        return decodeInto(document, supplier.get(), parent, extraInfo);
    }

    @Override
    public void decodeAndInherit(BsonDocument document, Map<String, V> t, Map<String, V> parent, ExtraInfo extraInfo) {
        decodeInto(document, t, parent, extraInfo);
    }

    // ==================== inherit (JSON) ====================

    @Nullable
    @Override
    public Map<String, V> decodeAndInheritJson(RawJsonReader reader, Map<String, V> parent, ExtraInfo extraInfo)
            throws IOException {
        return decodeJsonInto(reader, supplier.get(), parent, extraInfo);
    }

    @Override
    public void decodeAndInheritJson(RawJsonReader reader, Map<String, V> t, Map<String, V> parent, ExtraInfo extraInfo)
            throws IOException {
        decodeJsonInto(reader, t, parent, extraInfo);
    }

    // ==================== shared merge logic ====================

    /**
     * Whether a key is authoring metadata rather than a map entry. The engine's
     * {@code BuilderCodec} reserves the {@code $} prefix for its ignored keys
     * ({@code $Comment}, {@code $TODO}, {@code $Title}, ...), and this codec honours the same
     * convention so a keyed map can be documented inline like any structured object.
     */
    private static boolean isAuthoringCommentKey(@Nonnull String key) {
        return !key.isEmpty() && key.charAt(0) == '$';
    }

    @SuppressWarnings("unchecked")
    private Map<String, V> decodeInto(@Nonnull BsonDocument document, @Nonnull Map<String, V> out,
                                      @Nullable Map<String, V> parent, @Nonnull ExtraInfo extraInfo) {
        if (parent != null) {
            out.putAll(parent);
        }
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            String key = entry.getKey();
            BsonValue value = entry.getValue();
            if (isAuthoringCommentKey(key)) {
                // The value is already materialised in the document, so skipping it is simply
                // not handing it to the value codec; nothing else has to be consumed.
                continue;
            }
            extraInfo.pushKey(key);
            try {
                V parentValue = parent != null ? parent.get(key) : null;
                if (parentValue != null && valueCodec instanceof InheritCodec) {
                    out.put(key, ((InheritCodec<V>) valueCodec).decodeAndInherit(value.asDocument(), parentValue, extraInfo));
                } else {
                    out.put(key, valueCodec.decode(value, extraInfo));
                }
            } catch (Exception e) {
                throw new CodecException("Failed to decode", value, extraInfo, e);
            } finally {
                extraInfo.popKey();
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, V> decodeJsonInto(@Nonnull RawJsonReader reader, @Nonnull Map<String, V> out,
                                          @Nullable Map<String, V> parent, @Nonnull ExtraInfo extraInfo) throws IOException {
        if (parent != null) {
            out.putAll(parent);
        }
        reader.expect('{');
        reader.consumeWhiteSpace();
        if (reader.tryConsume('}')) {
            return out;
        }
        while (true) {
            String key = reader.readString();
            reader.consumeWhiteSpace();
            reader.expect(':');
            reader.consumeWhiteSpace();

            if (isAuthoringCommentKey(key)) {
                // The reader is a streaming parser, so the value MUST still be consumed or the
                // rest of the object parses off-by-one. This is exactly BuilderCodec's own
                // EntryType.IGNORE handling (skipField -> RawJsonReader.skipValue), which walks
                // whatever shape follows: string, number, bool, null, object, or array.
                reader.skipValue();
            } else {
                extraInfo.pushKey(key, reader);
                try {
                    V parentValue = parent != null ? parent.get(key) : null;
                    if (parentValue != null && valueCodec instanceof InheritCodec) {
                        out.put(key, ((InheritCodec<V>) valueCodec).decodeAndInheritJson(reader, parentValue, extraInfo));
                    } else {
                        out.put(key, valueCodec.decodeJson(reader, extraInfo));
                    }
                } catch (Exception e) {
                    throw new CodecException("Failed to decode", reader, extraInfo, e);
                } finally {
                    extraInfo.popKey();
                }
            }
            reader.consumeWhiteSpace();
            if (reader.tryConsumeOrExpect('}', ',')) {
                return out;
            }
            reader.consumeWhiteSpace();
        }
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        ObjectSchema schema = new ObjectSchema();
        schema.setTitle("Map");
        schema.setAdditionalProperties(context.refDefinition(valueCodec));
        return schema;
    }
}
