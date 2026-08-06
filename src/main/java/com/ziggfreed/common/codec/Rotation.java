package com.ziggfreed.common.codec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The ONE rotation codec leaf: a nested {@code {Yaw, Pitch, Roll}} group of independently nullable
 * DEGREES, matching the engine's own rotation vocabulary (the vanilla
 * {@code SpawnRotationOffset: {Pitch, Yaw, Roll}} authoring shape) rather than re-spelling the
 * three axes as {@code X}/{@code Y}/{@code Z} - the engine reserves those for POSITION.
 *
 * <ul>
 *   <li>{@link #yaw} turns the subject about the VERTICAL axis;
 *   <li>{@link #pitch} tips it forward/back (the "lay it flat" axis);
 *   <li>{@link #roll} tips it sideways about its own long axis.
 * </ul>
 *
 * <p>Authored in DEGREES (the human-authoring precedent every vanilla block-mount and spawn
 * offset follows), never raw radians; the consumer converts once at its own apply site and
 * composes them in the engine's Y-X-Z (yaw, then pitch, then roll) intrinsic euler order. Every
 * leaf is independently nullable (unauthored = 0) so partial authoring, native {@code Parent}
 * reuse, and any consumer-side per-leaf overlay convention all keep single-axis granularity.
 *
 * <p><b>Why this is minted rather than the engine's own {@code Rotation3f.CODEC}:</b> that codec
 * carries RADIANS in single-precision floats with a NaN sentinel for "unauthored", so reusing it
 * would silently change the authoring unit of every shipped rotation value. The leaf NAMES are
 * the engine's; the degrees carrier is this library's.
 */
public final class Rotation {

    @Nullable protected Double yaw;
    @Nullable protected Double pitch;
    @Nullable protected Double roll;

    public static final BuilderCodec<Rotation> CODEC = BuilderCodec.builder(Rotation.class, Rotation::new)
            .appendInherited(new KeyedCodec<>("Yaw", Codec.DOUBLE, false),
                    (o, v) -> o.yaw = v, o -> o.yaw, (o, p) -> o.yaw = p.yaw)
            .documentation("Yaw in degrees (turns about the vertical axis). Default 0.").add()
            .appendInherited(new KeyedCodec<>("Pitch", Codec.DOUBLE, false),
                    (o, v) -> o.pitch = v, o -> o.pitch, (o, p) -> o.pitch = p.pitch)
            .documentation("Pitch in degrees (tips forward/back - the 'lay it flat' axis). Default 0.").add()
            .appendInherited(new KeyedCodec<>("Roll", Codec.DOUBLE, false),
                    (o, v) -> o.roll = v, o -> o.roll, (o, p) -> o.roll = p.roll)
            .documentation("Roll in degrees (tips sideways about the subject's own long axis). Default 0.").add()
            .build();

    public Rotation() {
    }

    /** Java-side factory (leaf order matches the codec: yaw, then pitch, then roll). */
    @Nonnull
    public static Rotation of(@Nullable Double yaw, @Nullable Double pitch, @Nullable Double roll) {
        Rotation r = new Rotation();
        r.yaw = yaw;
        r.pitch = pitch;
        r.roll = roll;
        return r;
    }

    @Nullable
    public Double getYaw() {
        return yaw;
    }

    @Nullable
    public Double getPitch() {
        return pitch;
    }

    @Nullable
    public Double getRoll() {
        return roll;
    }

    /** {@link #yaw} in degrees, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveYawDegrees() {
        return yaw != null ? yaw : 0.0;
    }

    /** {@link #pitch} in degrees, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectivePitchDegrees() {
        return pitch != null ? pitch : 0.0;
    }

    /** {@link #roll} in degrees, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveRollDegrees() {
        return roll != null ? roll : 0.0;
    }
}
