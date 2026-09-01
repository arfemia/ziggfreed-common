package com.ziggfreed.common.codec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The ONE whole-block offset codec leaf: a nested {@code {X, Y, Z}} group of independently nullable
 * INTEGERS, for a field that addresses block cells (a relative block position, a cell in a
 * multi-block shape). Embed it wherever a mod's asset codec authors a block-grid shift
 * ({@code new KeyedCodec<>("Offset", Vec3i.CODEC, false)}).
 *
 * <p>Each consumer documents its OWN frame and axis convention at its own accessor: this type
 * carries the shape only, never a per-site convention. Every leaf is independently nullable
 * (unauthored = the consumer's own default, universally 0) so partial authoring
 * ({@code "Offset": {"Y": 1}}), native {@code Parent} reuse, and any consumer-side per-leaf overlay
 * convention all keep single-axis granularity.
 *
 * <p><b>Why this exists beside {@link Vec3}:</b> a block cell has no fractional coordinate, and
 * {@code Vec3}'s doubles would LOAD a fractional value happily and leave it to round somewhere far
 * from the file that authored it. The integer leaves refuse a decimal at decode (the engine's
 * integer codec throws on {@code 0.5}), so a fractional cell is a loud authoring error at load
 * rather than a silent off-by-one in the world.
 */
public final class Vec3i {

    @Nullable protected Integer x;
    @Nullable protected Integer y;
    @Nullable protected Integer z;

    public static final BuilderCodec<Vec3i> CODEC = BuilderCodec.builder(Vec3i.class, Vec3i::new)
            .appendInherited(new KeyedCodec<>("X", Codec.INTEGER, false),
                    (o, v) -> o.x = v, o -> o.x, (o, p) -> o.x = p.x)
            .documentation("The X component in whole blocks; unauthored means 0 (each axis is independently optional).").add()
            .appendInherited(new KeyedCodec<>("Y", Codec.INTEGER, false),
                    (o, v) -> o.y = v, o -> o.y, (o, p) -> o.y = p.y)
            .documentation("The Y component in whole blocks; unauthored means 0 (each axis is independently optional).").add()
            .appendInherited(new KeyedCodec<>("Z", Codec.INTEGER, false),
                    (o, v) -> o.z = v, o -> o.z, (o, p) -> o.z = p.z)
            .documentation("The Z component in whole blocks; unauthored means 0 (each axis is independently optional).").add()
            .build();

    public Vec3i() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static Vec3i of(@Nullable Integer x, @Nullable Integer y, @Nullable Integer z) {
        Vec3i v = new Vec3i();
        v.x = x;
        v.y = y;
        v.z = z;
        return v;
    }

    @Nullable
    public Integer getX() {
        return x;
    }

    @Nullable
    public Integer getY() {
        return y;
    }

    @Nullable
    public Integer getZ() {
        return z;
    }

    /** {@link #x}, reader-defaulted to {@code 0} when unauthored. */
    public int effectiveX() {
        return x != null ? x : 0;
    }

    /** {@link #y}, reader-defaulted to {@code 0} when unauthored. */
    public int effectiveY() {
        return y != null ? y : 0;
    }

    /** {@link #z}, reader-defaulted to {@code 0} when unauthored. */
    public int effectiveZ() {
        return z != null ? z : 0;
    }
}
