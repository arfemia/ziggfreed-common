package com.ziggfreed.common.stats.condition;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One kind of crowd control and how every control of it lands ({@link ControlKind}, read through
 * {@link ControlKinds}): the FILE NAME is the kind's id, the word an effect family's {@code Control}, a rule tag
 * ({@code ZigControlLands}, {@code ZigControlShrugs}) and a {@code ZigControl} condition name. The library ships its
 * twelve at {@code Server/ZiggfreedCommon/ControlKinds/}; a pack adds a kind by shipping a file of a new id, and the
 * owner layer is {@code mods/ziggfreedcommon/}{@value ControlKindConfig#OWNER_FILE} (the Control Kinds screen on
 * {@code /zigadmin}).
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/ControlKinds/Slow.json
 * { "RefusedByImmune": true, "Shrinks": true, "Ramps": true }
 * // a pack's own kind, which its effect family names as its Control
 * { "Description": "A fear: the foe runs from whoever cast it", "Shrinks": true, "Ramps": true,
 *   "OnBossOrElite": "Shrugs" }
 * }</pre>
 *
 * <p>Every control of the kind lands through the one landing: a foe wearing the game's {@code Immune} (or a stun
 * immunity of its own no window opens) refuses it when {@code RefusedByImmune}; it lasts less on a foe already
 * carrying Immunity when {@code Shrinks}; each landing adds Immunity when {@code Ramps} (the shared amount on the
 * Crowd Control screen, or this kind's {@code RampAmount}), so a foe held over and over turns Immune for a while; it
 * never lasts past {@code MaxSeconds}; and {@code OnBossOrElite} is what a boss or an elite takes of it unless a rule
 * of its own says otherwise.
 */
public final class ControlKindAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, ControlKindAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ControlKinds";

    /** What a boss or an elite takes of the kind unless written: an ability's own lands, a talent's does not. */
    public static final String USUAL = "Usual";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected String description;
    @Nullable protected Boolean refusedByImmune;
    @Nullable protected Boolean shrinks;
    @Nullable protected Boolean ramps;
    @Nullable protected Double rampAmount;
    @Nullable protected Double maxSeconds;
    @Nullable protected String onBossOrElite;

    public static final AssetBuilderCodec<String, ControlKindAsset> CODEC = AssetBuilderCodec.builder(
                    ControlKindAsset.class,
                    ControlKindAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Description", Codec.STRING, false),
                    (a, v) -> a.description = v, a -> a.description, (a, p) -> a.description = p.description)
            .documentation("What the kind is, in a line, for whoever authors a family or a rule: a stun, a slow, a "
                    + "fear. The id alone when unauthored.").add()
            .appendInherited(new KeyedCodec<>("RefusedByImmune", Codec.BOOLEAN, false),
                    (a, v) -> a.refusedByImmune = v, a -> a.refusedByImmune,
                    (a, p) -> a.refusedByImmune = p.refusedByImmune)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("True: a foe wearing the game's Immune effect, or a stun immunity of its own (an effect "
                    + "tagged ZigStunImmune) that no window it wears opens for this kind, takes none. True when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Shrinks", Codec.BOOLEAN, false),
                    (a, v) -> a.shrinks = v, a -> a.shrinks, (a, p) -> a.shrinks = p.shrinks)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: a landing lasts its time times 1 less the share of Immunity the foe already "
                    + "carries, so it is shorter each time on a foe held again soon. False when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Ramps", Codec.BOOLEAN, false),
                    (a, v) -> a.ramps = v, a -> a.ramps, (a, p) -> a.ramps = p.ramps)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: each landing adds Immunity to the foe (RampAmount, else the Crowd Control "
                    + "settings' RampAmount), and at 100 the game puts its Immune effect on. False when unauthored.")
            .add()
            .appendInherited(new KeyedCodec<>("RampAmount", Codec.DOUBLE, false),
                    (a, v) -> a.rampAmount = v, a -> a.rampAmount, (a, p) -> a.rampAmount = p.rampAmount)
            .documentation("The Immunity one landing of this kind adds, when it Ramps. The Crowd Control settings' "
                    + "RampAmount when unauthored.").add()
            .appendInherited(new KeyedCodec<>("MaxSeconds", Codec.DOUBLE, false),
                    (a, v) -> a.maxSeconds = v, a -> a.maxSeconds, (a, p) -> a.maxSeconds = p.maxSeconds)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("The longest one landing of this kind lasts, in seconds, after every rule (whoever put it "
                    + "on, the game's own effects included); 0 for no cap. 0 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("OnBossOrElite", Codec.STRING, false),
                    (a, v) -> a.onBossOrElite = v, a -> a.onBossOrElite, (a, p) -> a.onBossOrElite = p.onBossOrElite)
            .metadata(EditorSchema.oneOfDocumented(
                    "Lands", "From anyone, a talent's included",
                    USUAL, "An ability's own lands at the cast's own strength, a talent's never does",
                    "Shrugs", "From nothing, the game's own included"))
            .metadata(EditorSchema.defaultValue(USUAL))
            .documentation("What a boss or an elite takes of this kind unless a rule of its own (its NPC group's "
                    + "tags, a window or a phase its fight puts on) says otherwise. Usual when unauthored.").add()
            .build();

    public ControlKindAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The one-line description, or null. */
    @Nullable
    public String description() {
        return description == null || description.isBlank() ? null : description.trim();
    }

    /** Does a foe's immunity refuse it? True unless written false. */
    public boolean refusedByImmune() {
        return refusedByImmune == null || refusedByImmune;
    }

    /** Is a landing shorter by the Immunity share the foe carries? False unless written true. */
    public boolean shrinks() {
        return Boolean.TRUE.equals(shrinks);
    }

    /** Does a landing add Immunity? False unless written true. */
    public boolean ramps() {
        return Boolean.TRUE.equals(ramps);
    }

    /** The kind's own ramp amount, or null for the shared one; never below 0. */
    @Nullable
    public Double rampAmount() {
        return rampAmount == null || !Double.isFinite(rampAmount) ? null : Math.max(0.0, rampAmount);
    }

    /** The longest one landing lasts, in seconds; 0 for no cap. */
    public double maxSeconds() {
        return maxSeconds == null || !Double.isFinite(maxSeconds) ? 0.0 : Math.max(0.0, maxSeconds);
    }

    /**
     * What a boss or an elite takes of the kind by default: {@link ControlReading#LANDS}, {@link ControlReading#SHRUGS},
     * or null for usual (unwritten, {@value #USUAL}, or a word off the list).
     */
    @Nullable
    public ControlReading onBossOrElite() {
        return readingOf(onBossOrElite);
    }

    /** The {@code OnBossOrElite} as written, or null. */
    @Nullable
    public String rawOnBossOrElite() {
        return onBossOrElite;
    }

    /** {@code Lands} or {@code Shrugs} (any case) as a reading; null for anything else. Pure. */
    @Nullable
    static ControlReading readingOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String word = raw.trim().toLowerCase(Locale.ROOT);
        if (word.equals("lands")) {
            return ControlReading.LANDS;
        }
        return word.equals("shrugs") ? ControlReading.SHRUGS : null;
    }
}
