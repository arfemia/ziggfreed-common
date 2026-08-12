package com.ziggfreed.common.dialogue;

import java.io.IOException;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.InheritCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * A codec that stands in for one that cannot exist yet, resolving to the real codec the first time
 * anything actually decodes.
 *
 * <p>An asset's codec is a static field, built when its class loads; the dialogue body codec cannot
 * be, because its shape depends on the action / condition / shorthand vocabulary the consumer mods
 * register while they are starting up. This closes that gap without giving anything up: the server
 * finishes starting every plugin BEFORE it reads a single asset file, so by the time a dialogue is
 * decoded the vocabulary is complete and this hands over the finished codec.
 *
 * <p>It forwards {@link InheritCodec} as well, so a field declared through it still takes part in
 * {@code Parent} inheritance (the engine only offers the merge path to a field whose codec is an
 * inheriting one, and asking the real codec later would be too late).
 */
public final class DeferredCodec<T> implements Codec<T>, InheritCodec<T> {

    private final Supplier<Codec<T>> supplier;

    public DeferredCodec(@Nonnull Supplier<Codec<T>> supplier) {
        this.supplier = supplier;
    }

    /**
     * The real codec, asked for fresh every time rather than remembered here. The vocabulary can
     * still change (a mod registering later than it should), and a memo taken at the first read
     * would pin the shape from before that - which is the sort of staleness nobody would think to
     * look for. The supplier's own side does the caching.
     */
    @Nonnull
    public Codec<T> delegate() {
        return supplier.get();
    }

    @Nonnull
    private InheritCodec<T> inheriting() {
        Codec<T> codec = delegate();
        if (codec instanceof InheritCodec) {
            @SuppressWarnings("unchecked")
            InheritCodec<T> inherit = (InheritCodec<T>) codec;
            return inherit;
        }
        throw new IllegalStateException("Deferred codec resolved to a non-inheriting codec: " + codec);
    }

    @Override
    public T decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
        return delegate().decode(bsonValue, extraInfo);
    }

    @Nonnull
    @Override
    public BsonValue encode(@Nonnull T value, ExtraInfo extraInfo) {
        return delegate().encode(value, extraInfo);
    }

    @Nullable
    @Override
    public T decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return delegate().decodeJson(reader, extraInfo);
    }

    @Nullable
    @Override
    public T decodeAndInherit(BsonDocument document, T parent, ExtraInfo extraInfo) {
        return inheriting().decodeAndInherit(document, parent, extraInfo);
    }

    @Override
    public void decodeAndInherit(BsonDocument document, T t, T parent, ExtraInfo extraInfo) {
        inheriting().decodeAndInherit(document, t, parent, extraInfo);
    }

    @Nullable
    @Override
    public T decodeAndInheritJson(RawJsonReader reader, T parent, ExtraInfo extraInfo) throws IOException {
        return inheriting().decodeAndInheritJson(reader, parent, extraInfo);
    }

    @Override
    public void decodeAndInheritJson(RawJsonReader reader, T t, T parent, ExtraInfo extraInfo) throws IOException {
        inheriting().decodeAndInheritJson(reader, t, parent, extraInfo);
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return delegate().toSchema(context);
    }
}
