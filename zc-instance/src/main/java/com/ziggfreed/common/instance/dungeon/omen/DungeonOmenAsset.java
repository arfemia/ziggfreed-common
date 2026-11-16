package com.ziggfreed.common.instance.dungeon.omen;

import java.util.LinkedHashSet;
import java.util.List;
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
 * One omen, as a file: {@code Server/ZiggfreedCommon/DungeonOmens/<Id>.json}, native {@code Parent} for every leaf. An
 * omen is a run modifier a dungeon's week draws ({@code Omens} on a dungeon file, {@link OmenDraw}): the game's own
 * entity effects on the run's mobs or its players, and a switch any room's logic reads, the factor
 * {@code ziggfreedcommon:dungeon_omen} with the omen's id as its {@code Param} ({@link RunOmens}).
 *
 * <pre>{@code
 * {
 *   "Mobs": { "Effects": ["MyPack_Omen_Fortified"] },
 *   "Players": { "Effects": ["MyPack_Omen_Heavy_Feet"] },
 *   "Text": { "NameKey": "mypack.omen.fortified.name", "DescriptionKey": "mypack.omen.fortified.description" }
 * }
 * }</pre>
 *
 * <p>An omen with no effects is a switch alone: a volume's {@code ZigRequires} reads it, so a room opens a side door, or
 * raises an extra wave, only in a run that drew it.
 */
public final class DungeonOmenAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DungeonOmenAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DungeonOmens";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Effects mobs;
    @Nullable private Effects players;
    @Nullable private Text text;

    public static final AssetBuilderCodec<String, DungeonOmenAsset> CODEC = AssetBuilderCodec.builder(
                    DungeonOmenAsset.class,
                    DungeonOmenAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Mobs", Effects.CODEC, false),
                    (a, v) -> a.mobs = v, a -> a.mobs, (a, p) -> a.mobs = p.mobs)
            .documentation("What the omen puts on every creature that comes into being in a run that drew it: its "
                    + "foes, its bosses and anything their fights raise. Give these effects no end (Infinite), "
                    + "as a creature keeps one for its life. Unauthored, the omen leaves creatures alone.").add()
            .appendInherited(new KeyedCodec<>("Players", Effects.CODEC, false),
                    (a, v) -> a.players = v, a -> a.players, (a, p) -> a.players = p.players)
            .documentation("What the omen puts on every player standing in a run that drew it. The run renews each "
                    + "effect every second while the player stays, so give it a Duration of a few seconds: it then "
                    + "wears off soon after they leave. Unauthored, the omen leaves players alone.").add()
            .appendInherited(new KeyedCodec<>("Text", Text.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What players read the omen as, at the dungeon's gate and as they arrive, by "
                    + "localization key.").add()
            .build();

    public DungeonOmenAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** {@code Mobs.Effects}: the effect ids put on the run's creatures, trimmed, blanks and repeats dropped. */
    @Nonnull
    public List<String> mobEffects() {
        return Effects.ids(mobs);
    }

    /** {@code Players.Effects}: the effect ids held on the run's players, trimmed, blanks and repeats dropped. */
    @Nonnull
    public List<String> playerEffects() {
        return Effects.ids(players);
    }

    /** {@code Text.NameKey}, trimmed, or null when the file names none (the omen then reads as its id). */
    @Nullable
    public String nameKey() {
        return blankToNull(text == null ? null : text.nameKey);
    }

    /** {@code Text.DescriptionKey}, trimmed, or null when the file names none. */
    @Nullable
    public String descriptionKey() {
        return blankToNull(text == null ? null : text.descriptionKey);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ==================== Effects ====================

    /** The game's own entity effects an omen puts on. */
    public static final class Effects {

        @Nullable protected String[] effects;

        public static final BuilderCodec<Effects> CODEC = BuilderCodec.builder(Effects.class, Effects::new)
                .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                        (o, v) -> o.effects = v, o -> o.effects, (o, p) -> o.effects = p.effects)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("Entity effect ids (files under Server/Entity/Effects/), the game's own or a pack's. "
                        + "An id no loaded effect answers to is reported and skipped. This is ONE leaf: authoring it "
                        + "replaces an inherited list whole.").add()
                .build();

        public Effects() {
        }

        @Nonnull
        static List<String> ids(@Nullable Effects group) {
            String[] raw = group == null ? null : group.effects;
            if (raw == null || raw.length == 0) {
                return List.of();
            }
            Set<String> out = new LinkedHashSet<>();
            for (String effect : raw) {
                String id = blankToNull(effect);
                if (id != null) {
                    out.add(id);
                }
            }
            return List.copyOf(out);
        }
    }

    // ==================== Text ====================

    /** What players read the omen as. */
    public static final class Text {

        @Nullable protected String nameKey;
        @Nullable protected String descriptionKey;

        public static final BuilderCodec<Text> CODEC = BuilderCodec.builder(Text.class, Text::new)
                .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                        (o, v) -> o.nameKey = v, o -> o.nameKey, (o, p) -> o.nameKey = p.nameKey)
                .documentation("The localization key the omen's name reads from, such as Bolstering. Unauthored "
                        + "shows its id.").add()
                .appendInherited(new KeyedCodec<>("DescriptionKey", Codec.STRING, false),
                        (o, v) -> o.descriptionKey = v, o -> o.descriptionKey,
                        (o, p) -> o.descriptionKey = p.descriptionKey)
                .documentation("The localization key of a short line saying what the omen does to a run, shown under "
                        + "its name at the gate.").add()
                .build();

        public Text() {
        }
    }
}
