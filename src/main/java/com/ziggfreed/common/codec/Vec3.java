package com.ziggfreed.common.codec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The ONE spatial-offset codec leaf: a nested {@code {X, Y, Z}} group of independently nullable
 * doubles, matching the engine's own {@code Vector3d} leaf NAMES. Embed it wherever a mod's asset
 * codec authors a relative shift ({@code new KeyedCodec<>("Offset", Vec3.CODEC, false)}).
 *
 * <p>Each consumer documents its OWN frame and units at its own accessor: this type carries the
 * shape only, never a per-site convention. Every leaf is independently nullable (unauthored = the
 * consumer's own default, universally 0) so partial authoring ({@code "Offset": {"Y": -0.1}}),
 * native {@code Parent} reuse, and any consumer-side per-leaf overlay convention all keep
 * single-axis granularity.
 *
 * <p><b>Why this is minted rather than the engine's own {@code Vector3dUtil.CODEC}:</b> that codec
 * decodes into a JOML {@code Vector3d} whose {@code x}/{@code y}/{@code z} are PRIMITIVE doubles,
 * so an unauthored leaf is indistinguishable from an authored {@code 0.0} - which erases
 * null-means-inherit overlay granularity - and it attaches a non-null validator to all three
 * leaves, which REJECTS partial authoring instead of loading it. The leaf NAMES are the engine's;
 * only the nullable-Double carrier is this library's.
 */
public final class Vec3 {

    @Nullable protected Double x;
    @Nullable protected Double y;
    @Nullable protected Double z;

    public static final BuilderCodec<Vec3> CODEC = BuilderCodec.builder(Vec3.class, Vec3::new)
            .appendInherited(new KeyedCodec<>("X", Codec.DOUBLE, false),
                    (o, v) -> o.x = v, o -> o.x, (o, p) -> o.x = p.x)
            .documentation("The X component; unauthored means 0 (each axis is independently optional).").add()
            .appendInherited(new KeyedCodec<>("Y", Codec.DOUBLE, false),
                    (o, v) -> o.y = v, o -> o.y, (o, p) -> o.y = p.y)
            .documentation("The Y component; unauthored means 0 (each axis is independently optional).").add()
            .appendInherited(new KeyedCodec<>("Z", Codec.DOUBLE, false),
                    (o, v) -> o.z = v, o -> o.z, (o, p) -> o.z = p.z)
            .documentation("The Z component; unauthored means 0 (each axis is independently optional).").add()
            .build();

    public Vec3() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static Vec3 of(@Nullable Double x, @Nullable Double y, @Nullable Double z) {
        Vec3 v = new Vec3();
        v.x = x;
        v.y = y;
        v.z = z;
        return v;
    }

    @Nullable
    public Double getX() {
        return x;
    }

    @Nullable
    public Double getY() {
        return y;
    }

    @Nullable
    public Double getZ() {
        return z;
    }

    /** {@link #x}, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveX() {
        return x != null ? x : 0.0;
    }

    /** {@link #y}, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveY() {
        return y != null ? y : 0.0;
    }

    /** {@link #z}, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveZ() {
        return z != null ? z : 0.0;
    }
}
