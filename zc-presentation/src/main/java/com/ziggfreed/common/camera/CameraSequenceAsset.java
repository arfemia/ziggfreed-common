package com.ziggfreed.common.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.protocol.EasingType;
import com.ziggfreed.common.codec.Vec3;

/**
 * A short camera move a pack authors as data, played on a player's own client through the engine's native
 * camera sequence ({@code modules.camera.CameraSequenceBuilder}, the same path the game's cinematic teleport
 * uses): one file per sequence under {@code Server/ZiggfreedCommon/CameraSequences/<Id>.json}.
 *
 * <p>Each keyframe is where the camera flies to and what it looks at, reached over {@code Seconds} from the
 * keyframe before it (the first one from the player's own view). Positions are in blocks from an origin the
 * caller supplies, so one sequence fits every copy of a place: a dungeon area passes the spot its prefab was
 * pasted at. A keyframe with {@code ToPlayer} flies back to the player's own eyes and facing, so ending on one
 * hands the view back without a jump. A very short {@code Seconds} (0.05) is a cut.
 *
 * <pre>{@code
 * {
 *   "LockInput": true,
 *   "HidePlayer": true,
 *   "Keyframes": [
 *     { "Position": { "X": 0.5, "Y": 20, "Z": 12 }, "LookAt": { "X": 0.5, "Y": 2, "Z": 50 }, "Seconds": 0.05 },
 *     { "Position": { "X": 0.5, "Y": 14, "Z": 40 }, "LookAt": { "X": 0.5, "Y": 1, "Z": 64 }, "Seconds": 3,
 *       "Easing": "SineInOut" },
 *     { "ToPlayer": true, "Seconds": 1.5 }
 *   ]
 * }
 * }</pre>
 *
 * <p>The camera always returns to the normal gameplay view when the sequence ends, and {@link CameraSequences}
 * hands it back early when the player leaves or the client never reports the end. Tune a move by changing a
 * keyframe's {@code Seconds} (longer is gentler) or its {@code Easing} (any of the engine's easing names,
 * {@code Linear}, {@code SineIn}, {@code SineOut}, {@code SineInOut}, {@code CubicInOut} and so on).
 */
public final class CameraSequenceAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, CameraSequenceAsset>> {

    /** The store's folder under {@code Server/}. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/CameraSequences";

    /** A keyframe's travel time when it writes none. */
    public static final float DEFAULT_SECONDS = 1.0f;

    /** A keyframe's easing when it writes none, or names one the engine does not have. */
    public static final EasingType DEFAULT_EASING = EasingType.SineInOut;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean lockInput;
    @Nullable private Boolean hidePlayer;
    @Nullable private Keyframe[] keyframes;

    public static final AssetBuilderCodec<String, CameraSequenceAsset> CODEC = AssetBuilderCodec.builder(
                    CameraSequenceAsset.class,
                    CameraSequenceAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("LockInput", Codec.BOOLEAN, false),
                    (a, v) -> a.lockInput = v, a -> a.lockInput)
            .documentation("Whether the player's movement and look are held while the camera is away, so they do "
                    + "not walk off unseen. Unauthored means true. Input comes back the moment the sequence ends.")
            .add()
            .append(new KeyedCodec<>("HidePlayer", Codec.BOOLEAN, false),
                    (a, v) -> a.hidePlayer = v, a -> a.hidePlayer)
            .documentation("Whether the player's own model is hidden from their camera during the sequence. "
                    + "Unauthored means true.")
            .add()
            .append(new KeyedCodec<>("Keyframes", new ArrayCodec<>(Keyframe.CODEC, Keyframe[]::new), false),
                    (a, v) -> a.keyframes = v, a -> a.keyframes)
            .documentation("The camera's path, in order. A sequence with none plays nothing.")
            .add()
            .build();

    @Nullable
    private static volatile DefaultAssetMap<String, CameraSequenceAsset> assetMap;

    public CameraSequenceAsset() {
    }

    /** The loaded sequences, or null before the store is registered (a unit-test JVM). */
    @Nullable
    @SuppressWarnings("unchecked")
    public static DefaultAssetMap<String, CameraSequenceAsset> getAssetMap() {
        DefaultAssetMap<String, CameraSequenceAsset> map = assetMap;
        if (map == null) {
            var store = AssetRegistry.getAssetStore(CameraSequenceAsset.class);
            if (store == null) {
                return null;
            }
            map = (DefaultAssetMap<String, CameraSequenceAsset>) store.getAssetMap();
            assetMap = map;
        }
        return map;
    }

    /** The sequence {@code id} names, or null when no pack loaded one by that id. */
    @Nullable
    public static CameraSequenceAsset get(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, CameraSequenceAsset> map = getAssetMap();
        return map == null ? null : map.getAsset(id.trim());
    }

    @Override
    public String getId() {
        return id;
    }

    /** {@code LockInput}, true unauthored. */
    public boolean lockInput() {
        return lockInput == null || lockInput;
    }

    /** {@code HidePlayer}, true unauthored. */
    public boolean hidePlayer() {
        return hidePlayer == null || hidePlayer;
    }

    /** The keyframes in order, an empty list when none are written. */
    @Nonnull
    public List<Keyframe> keyframes() {
        if (keyframes == null) {
            return List.of();
        }
        List<Keyframe> out = new ArrayList<>(keyframes.length);
        for (Keyframe k : keyframes) {
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    /** How long the whole sequence plays, in seconds: the sum of its keyframes' travel times. */
    public float totalSeconds() {
        float total = 0f;
        for (Keyframe k : keyframes()) {
            total += k.seconds();
        }
        return total;
    }

    /**
     * One stop on the camera's path: where it flies to ({@code Position}), what it looks at ({@code LookAt}), or
     * the player's own view ({@code ToPlayer}), over {@code Seconds} with {@code Easing}.
     */
    public static final class Keyframe {
        @Nullable protected Vec3 position;
        @Nullable protected Vec3 lookAt;
        @Nullable protected Float seconds;
        @Nullable protected String easing;
        @Nullable protected Float fov;
        @Nullable protected Boolean toPlayer;

        public static final BuilderCodec<Keyframe> CODEC = BuilderCodec.builder(Keyframe.class, Keyframe::new)
                .append(new KeyedCodec<>("Position", Vec3.CODEC, false),
                        (o, v) -> o.position = v, o -> o.position)
                .documentation("Where the camera flies to, in blocks from the origin the sequence plays at. "
                        + "Unauthored keeps the previous keyframe's spot.").add()
                .append(new KeyedCodec<>("LookAt", Vec3.CODEC, false),
                        (o, v) -> o.lookAt = v, o -> o.lookAt)
                .documentation("The point the camera looks at, in blocks from the same origin. Unauthored keeps "
                        + "the previous keyframe's look.").add()
                .append(new KeyedCodec<>("Seconds", Codec.FLOAT, false),
                        (o, v) -> o.seconds = v, o -> o.seconds)
                .documentation("How long the camera takes to get here from the keyframe before. 0.05 is a cut. "
                        + "Unauthored means 1.").add()
                .append(new KeyedCodec<>("Easing", Codec.STRING, false),
                        (o, v) -> o.easing = v, o -> o.easing)
                .documentation("How the move speeds up and slows down: an engine easing name such as Linear, "
                        + "SineIn, SineOut or SineInOut. Unauthored, or a name the engine lacks, means SineInOut.")
                .add()
                .append(new KeyedCodec<>("Fov", Codec.FLOAT, false),
                        (o, v) -> o.fov = v, o -> o.fov)
                .documentation("A field of view the camera eases to over this move. Unauthored keeps the "
                        + "player's own.").add()
                .append(new KeyedCodec<>("ToPlayer", Codec.BOOLEAN, false),
                        (o, v) -> o.toPlayer = v, o -> o.toPlayer)
                .documentation("True flies to the player's own eyes and facing instead of Position and LookAt, "
                        + "so a last keyframe with it hands the view back without a jump.").add()
                .build();

        public Keyframe() {
        }

        @Nullable
        public Vec3 position() {
            return position;
        }

        @Nullable
        public Vec3 lookAt() {
            return lookAt;
        }

        /** {@code Seconds}, at least 0.01 so the engine never divides by zero; 1 unauthored. */
        public float seconds() {
            float s = seconds == null ? DEFAULT_SECONDS : seconds;
            return Float.isFinite(s) ? Math.max(0.01f, s) : DEFAULT_SECONDS;
        }

        /** {@code Easing} as the engine's type, {@link #DEFAULT_EASING} unauthored or unknown. */
        @Nonnull
        public EasingType easing() {
            return easingOf(easing);
        }

        /** The raw {@code Easing} name as authored, or null. */
        @Nullable
        public String easingName() {
            return easing;
        }

        @Nullable
        public Float fov() {
            return fov;
        }

        public boolean toPlayer() {
            return toPlayer != null && toPlayer;
        }
    }

    /** The engine easing {@code name} spells, matched without case, else {@link #DEFAULT_EASING}. */
    @Nonnull
    public static EasingType easingOf(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_EASING;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (EasingType type : EasingType.values()) {
            if (type.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return type;
            }
        }
        return DEFAULT_EASING;
    }
}
