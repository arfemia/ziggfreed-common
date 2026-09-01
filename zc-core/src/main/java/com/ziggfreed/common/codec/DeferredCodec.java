package com.ziggfreed.common.codec;

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
 * A codec that stands in for one that cannot be referenced yet, resolving to the real codec the
 * first time anything actually decodes. Two situations want it, for the same reason - a codec is a
 * static field, built when its class loads, and the real codec is not safe to touch at that moment:
 *
 * <ul>
 *   <li><b>The real codec does not EXIST yet.</b> A field's shape depends on a vocabulary consumer
 *       mods register while they are starting up (the dialogue engine's conversation fields). The
 *       server finishes starting every plugin BEFORE it reads a single asset file, so by decode
 *       time the vocabulary is complete and this hands over the finished codec.</li>
 *   <li><b>The real codec exists but must not be CLASS-LOADED yet.</b> An engine codec constant
 *       (say {@code ItemStack.CODEC}) drags its class's whole static graph - asset maps, validator
 *       caches, the engine log manager - into any JVM that merely class-loads the type referencing
 *       it. Behind this forwarder the embedding type stays loadable anywhere (a bare unit JVM,
 *       early boot); the engine class initializes only when a value actually flows.</li>
 * </ul>
 *
 * <p>The delegate is asked for FRESH every time rather than remembered here: a vocabulary can
 * still change (a mod registering later than it should), and a memo taken at the first read would
 * pin the shape from before that - which is the sort of staleness nobody would think to look for.
 * The supplier's own side does the caching (a constant field read costs nothing anyway).
 *
 * <p>It forwards {@link InheritCodec} as well, so a field declared through it still takes part in
 * {@code Parent} inheritance (the engine only offers the merge path to a field whose codec is an
 * inheriting one, and asking the real codec later would be too late).
 *
 * <p><b>A delegate that does not inherit still works.</b> Whether a codec merges with its parent or
 * replaces it wholesale is decided by the codec, not by this forwarder, and both kinds are declared
 * through here (a keyed map merges per key; an array replaces). Since the engine asks this class
 * rather than the delegate, this class asks the delegate the SAME question the engine would have
 * asked it - merge when it can, plain decode when it cannot - so a field's inherit behaviour is the
 * delegate's own either way.
 */
public final class DeferredCodec<T> implements Codec<T>, InheritCodec<T> {

    private final Supplier<Codec<T>> supplier;

    public DeferredCodec(@Nonnull Supplier<Codec<T>> supplier) {
        this.supplier = supplier;
    }

    /**
     * The real codec, asked for fresh every time rather than remembered here (see the class
     * javadoc for why a memo would be the wrong kind of helpful).
     */
    @Nonnull
    public Codec<T> delegate() {
        return supplier.get();
    }

    /** The resolved codec as an inheriting one, or null when it merges nothing and replaces instead. */
    @Nullable
    private InheritCodec<T> inheritingOrNull() {
        Codec<T> codec = delegate();
        if (codec instanceof InheritCodec) {
            @SuppressWarnings("unchecked")
            InheritCodec<T> inherit = (InheritCodec<T>) codec;
            return inherit;
        }
        return null;
    }

    /**
     * The resolved codec as an inheriting one, for the two forms that fill a value the caller has
     * ALREADY created. There is no way to honour those with a replacing codec, and the engine only
     * reaches them for a delegate it has itself confirmed is a merging one, so arriving here with
     * anything else means the wiring is wrong rather than the file.
     */
    @Nonnull
    private InheritCodec<T> inheriting() {
        InheritCodec<T> inherit = inheritingOrNull();
        if (inherit == null) {
            throw new IllegalStateException("Deferred codec resolved to a non-inheriting codec: " + delegate());
        }
        return inherit;
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
        InheritCodec<T> inherit = inheritingOrNull();
        if (inherit == null) {
            return delegate().decode(document, extraInfo);
        }
        return inherit.decodeAndInherit(document, parent, extraInfo);
    }

    @Override
    public void decodeAndInherit(BsonDocument document, T t, T parent, ExtraInfo extraInfo) {
        inheriting().decodeAndInherit(document, t, parent, extraInfo);
    }

    @Nullable
    @Override
    public T decodeAndInheritJson(RawJsonReader reader, T parent, ExtraInfo extraInfo) throws IOException {
        InheritCodec<T> inherit = inheritingOrNull();
        if (inherit == null) {
            return delegate().decodeJson(reader, extraInfo);
        }
        return inherit.decodeAndInheritJson(reader, parent, extraInfo);
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
