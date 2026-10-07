package com.ziggfreed.common.almanac.asset;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.google.gson.JsonElement;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.InheritCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.util.SafeLog;

/**
 * A group that is ONE piece under {@code Parent} and under the owner's file: a layer that writes it
 * replaces the inherited one whole (none of the parent's leaves leak into it), and a layer that writes
 * it badly keeps the inherited one, with a warning, instead of failing the whole file.
 *
 * <p>It is an {@link InheritCodec} only so the engine hands it the inherited value: it never merges
 * with it. The value is read whole first ({@link JsonTreeCodec}, verbatim) and decoded from that copy,
 * so a failed decode leaves the file's reader where the next key begins, and the key stack is put back
 * the way the decode found it.
 */
final class WholeLeafCodec<T> implements Codec<T>, InheritCodec<T> {

    private final BuilderCodec<T> delegate;
    private final String noun;

    WholeLeafCodec(@Nonnull BuilderCodec<T> delegate, @Nonnull String noun) {
        this.delegate = delegate;
        this.noun = noun;
    }

    @Override
    @Nullable
    public T decode(BsonValue bsonValue, ExtraInfo extraInfo) {
        return delegate.decode(bsonValue, extraInfo);
    }

    @Override
    public BsonValue encode(T value, ExtraInfo extraInfo) {
        return delegate.encode(value, extraInfo);
    }

    @Override
    @Nullable
    public T decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return decodeOr(JsonTreeCodec.object().decodeJson(reader, extraInfo), null, extraInfo);
    }

    @Override
    @Nullable
    public T decodeAndInherit(BsonDocument document, T parent, ExtraInfo extraInfo) {
        return decodeOr(JsonTreeCodec.object().decode(document, extraInfo), parent, extraInfo);
    }

    @Override
    public void decodeAndInherit(BsonDocument document, T t, T parent, ExtraInfo extraInfo) {
        delegate.decodeAndInherit(document, t, null, extraInfo);
    }

    @Override
    @Nullable
    public T decodeAndInheritJson(RawJsonReader reader, T parent, ExtraInfo extraInfo) throws IOException {
        return decodeOr(JsonTreeCodec.object().decodeJson(reader, extraInfo), parent, extraInfo);
    }

    @Override
    public void decodeAndInheritJson(RawJsonReader reader, T t, T parent, ExtraInfo extraInfo) throws IOException {
        delegate.decodeAndInheritJson(reader, t, null, extraInfo);
    }

    @Override
    @Nonnull
    public Schema toSchema(@Nonnull SchemaContext context) {
        return delegate.toSchema(context);
    }

    /** {@code value} read fresh, or {@code fallback} (with a warning) when it will not read. */
    @Nullable
    private T decodeOr(@Nullable JsonElement value, @Nullable T fallback, @Nonnull ExtraInfo extraInfo) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        int keys = extraInfo.getKeysSize();
        int ignored = extraInfo.getIgnoredUnusedSize();
        try {
            return delegate.decodeJson(RawJsonReader.fromJsonString(value.toString()), extraInfo);
        } catch (Throwable t) {
            while (extraInfo.getKeysSize() > keys) {
                extraInfo.popKey();
            }
            while (extraInfo.getIgnoredUnusedSize() > ignored) {
                extraInfo.popIgnoredUnusedKey();
            }
            SafeLog.warn("[almanac] " + extraInfo.peekKey() + ": the " + noun + " will not read ("
                    + t.getMessage() + "), so " + (fallback == null ? "there is none" : "the inherited one stands"));
            return fallback;
        }
    }
}
