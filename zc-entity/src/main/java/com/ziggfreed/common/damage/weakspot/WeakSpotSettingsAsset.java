package com.ziggfreed.common.damage.weakspot;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The server's one word on where a hit is a weak spot ({@link WeakSpots}): the head zone, the backstab arc, the
 * vanilla effects that open a window, and the combat text. The FILE NAME is the record's id, and the record every
 * hit reads is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/WeakSpotSettings/Default.json}, which this library ships; a pack's
 * same-id file wins by pack order, and the owner layer is {@code mods/ziggfreedcommon/weak-spots.json}:
 * <pre>{@code
 * { "Default": { "Backstab": { "Arc": 120 } } }
 * }</pre>
 *
 * <p>An effect of a mod's own opens a window through its own native {@code Tags} ({@value WeakSpots#TAG}, R751);
 * {@code Windows.Effects} is only for effects nobody can tag in their own file, vanilla's.
 */
public final class WeakSpotSettingsAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, WeakSpotSettingsAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/WeakSpotSettings";

    /** The id of the record every hit reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** How far past the head box a hit still counts, in blocks, unless the record says otherwise. */
    public static final double DEFAULT_HEAD_MARGIN = 0.1;

    /** The rear arc a backstab lands in, in degrees, unless the record says otherwise: the dagger's own (180 +/- 80). */
    public static final double DEFAULT_BACKSTAB_ARC = 160.0;

    /** The combat text's colour unless the record says otherwise. */
    public static final String DEFAULT_TEXT_COLOR = "#FF9A2E";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Head head;
    @Nullable protected Backstab backstab;
    @Nullable protected Windows windows;
    @Nullable protected Text combatText;

    public static final AssetBuilderCodec<String, WeakSpotSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    WeakSpotSettingsAsset.class,
                    WeakSpotSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Head", Head.CODEC, false),
                    (a, v) -> a.head = v, a -> a.head, (a, p) -> a.head = p.head)
            .documentation("Headshots: a hit landing inside the target's Head detail box (the box its model "
                    + "declares). A creature whose model has no Head box takes none.").add()
            .appendInherited(new KeyedCodec<>("Backstab", Backstab.CODEC, false),
                    (a, v) -> a.backstab = v, a -> a.backstab, (a, p) -> a.backstab = p.backstab)
            .documentation("Backstabs: a hit from behind the target, read the way the game's own dagger "
                    + "backstab reads it, from where the attacker stands against the way the target faces.").add()
            .appendInherited(new KeyedCodec<>("Windows", Windows.CODEC, false),
                    (a, v) -> a.windows = v, a -> a.windows, (a, p) -> a.windows = p.windows)
            .documentation("Effects that make every hit on whoever carries them a weak spot while they last, "
                    + "beside every effect tagged ZigWeakSpot in its own file.").add()
            .appendInherited(new KeyedCodec<>("CombatText", Text.CODEC, false),
                    (a, v) -> a.combatText = v, a -> a.combatText, (a, p) -> a.combatText = p.combatText)
            .documentation("The floating word the attacker sees over a weak-spot hit.").add()
            .build();

    public WeakSpotSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Do headshots count? */
    public boolean headEnabled() {
        return head == null || head.enabled == null || head.enabled;
    }

    /** How far past the head box a hit still counts, in blocks, 0 or more. */
    public double headMargin() {
        Double margin = head == null ? null : head.margin;
        return margin == null || !Double.isFinite(margin) ? DEFAULT_HEAD_MARGIN : Math.max(0.0, margin);
    }

    /** Do backstabs count? */
    public boolean backstabEnabled() {
        return backstab == null || backstab.enabled == null || backstab.enabled;
    }

    /** The rear arc in degrees, held to [0, 360]. */
    public double backstabArc() {
        Double arc = backstab == null ? null : backstab.arc;
        return arc == null || !Double.isFinite(arc) ? DEFAULT_BACKSTAB_ARC : Math.max(0.0, Math.min(360.0, arc));
    }

    /** The listed window effects, lower-cased, in the order written. */
    @Nonnull
    public Set<String> windowEffects() {
        String[] listed = windows == null ? null : windows.effects;
        Set<String> out = new LinkedHashSet<>();
        if (listed != null) {
            for (String effect : listed) {
                if (effect != null && !effect.isBlank()) {
                    out.add(effect.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** Is the combat text shown? */
    public boolean textEnabled() {
        return combatText == null || combatText.enabled == null || combatText.enabled;
    }

    /** The combat text's colour as written, else {@link #DEFAULT_TEXT_COLOR}. */
    @Nonnull
    public String textColor() {
        String color = combatText == null ? null : combatText.color;
        return color == null || color.isBlank() ? DEFAULT_TEXT_COLOR : color.trim();
    }

    /** The Head group. */
    public static final class Head {

        @Nullable protected Boolean enabled;
        @Nullable protected Double margin;

        public static final BuilderCodec<Head> CODEC = BuilderCodec.builder(Head.class, Head::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether a hit to the head is a weak spot. Unauthored means true.").add()
                .appendInherited(new KeyedCodec<>("Margin", Codec.DOUBLE, false),
                        (o, v) -> o.margin = v, o -> o.margin, (o, p) -> o.margin = p.margin)
                .metadata(EditorSchema.defaultValue(DEFAULT_HEAD_MARGIN))
                .documentation("How far outside the head box, in blocks, a hit still counts: an arrow's hit is "
                        + "read where the arrow is when its own box touches the head, a little outside it. 0.1 "
                        + "when unauthored.").add()
                .build();

        public Head() {
        }
    }

    /** The Backstab group. */
    public static final class Backstab {

        @Nullable protected Boolean enabled;
        @Nullable protected Double arc;

        public static final BuilderCodec<Backstab> CODEC = BuilderCodec.builder(Backstab.class, Backstab::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether a hit from behind is a weak spot. Unauthored means true.").add()
                .appendInherited(new KeyedCodec<>("Arc", Codec.DOUBLE, false),
                        (o, v) -> o.arc = v, o -> o.arc, (o, p) -> o.arc = p.arc)
                .metadata(EditorSchema.defaultValue(DEFAULT_BACKSTAB_ARC))
                .documentation("How wide the arc behind the target is, in degrees: 160, the dagger's own "
                        + "backstab, counts an attacker anywhere within 80 degrees of straight behind. 160 when "
                        + "unauthored; 0 means none.").add()
                .build();

        public Backstab() {
        }
    }

    /** The Windows group. */
    public static final class Windows {

        @Nullable protected String[] effects;

        public static final BuilderCodec<Windows> CODEC = BuilderCodec.builder(Windows.class, Windows::new)
                .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                        (o, v) -> o.effects = v, o -> o.effects, (o, p) -> o.effects = p.effects)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("Effect ids (the game's own, which no other file can tag) under which every hit "
                        + "is a weak spot for as long as the effect lasts: a stun, a flinch's lockout, a freeze, a "
                        + "boss broken down. An id this server does not have opens nothing. Under Parent this list "
                        + "replaces the parent's whole.").add()
                .build();

        public Windows() {
        }
    }

    /** The CombatText group. */
    public static final class Text {

        @Nullable protected Boolean enabled;
        @Nullable protected String color;

        public static final BuilderCodec<Text> CODEC = BuilderCodec.builder(Text.class, Text::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the attacker sees a word over a weak-spot hit. Unauthored means true.")
                .add()
                .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                        (o, v) -> o.color = v, o -> o.color, (o, p) -> o.color = p.color)
                .metadata(EditorSchema.defaultValue(DEFAULT_TEXT_COLOR))
                .documentation("The word's colour, as #RRGGBB. #FF9A2E when unauthored.").add()
                .build();

        public Text() {
        }
    }
}
