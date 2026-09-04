package com.ziggfreed.common.progress.asset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.icon.IconSpec;

/**
 * One kind of quest or achievement step, written as content instead of code:
 * {@code Server/ZiggfreedCommon/ObjectiveKinds/<Id>.json}. The kind's id is the FILENAME, and it is
 * the same id authored content writes as its {@code Kind}.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/ObjectiveKinds/Kill_Entity.json
 * {
 *   "$Comment": "Defeating a creature.",
 *   "TargetNames": { "Entity": true },
 *   "Presentation": {
 *     "Icon": { "ItemId": "Weapon_Sword_Crude" },
 *     "TargetIcons": { "Trork": { "TexturePath": "Icons/ModelsGenerated/Trork_Brawler.png" } }
 *   }
 * }
 * }</pre>
 *
 * <p>This is the ONE place a kind is described. What it counts and how, what its target names, the
 * sentence it reads as and the picture beside it all live here, so a kind cannot be half-described
 * in one file and half in another.
 *
 * <p><b>Every leaf is optional and merges over what code registered.</b> A mod registering a kind in
 * Java states the defaults; a file states only what it changes. So a file adding nothing but a
 * picture keeps the arithmetic the mod registered, and an owner who wants one kind to stop counting
 * writes {@code "Producible": false} without having to restate anything else.
 */
public final class ObjectiveKindAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ObjectiveKindAsset>> {

    /** Where these files live, and the id the Asset Editor serves this type's pick list under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ObjectiveKinds";

    public static final String EDITOR_DATASET = "ziggfreedcommon:objectivekinds";

    /**
     * What an objective's TARGET names, which is what lets a surface DRAW a step: an item id is a
     * picture of itself, a creature id is a portrait, and a place is neither.
     *
     * <p>Independent flags rather than one choice, because a kind may genuinely be more than one - a
     * step naming a character names both somewhere to go and something with a face.
     */
    public static final class TargetNames {

        public static final BuilderCodec<TargetNames> CODEC =
                BuilderCodec.builder(TargetNames.class, TargetNames::new)
                        .documentation("What this kind's Target names, which decides how a step is drawn.")
                        .append(new KeyedCodec<>("Place", Codec.BOOLEAN, false),
                                (t, v) -> t.place = v, t -> t.place)
                        .documentation("Somewhere to go - a character, a location. A place is matched whole "
                                + "and is never drawn from.").add()
                        .append(new KeyedCodec<>("Item", Codec.BOOLEAN, false),
                                (t, v) -> t.item = v, t -> t.item)
                        .documentation("Something a player can hold - an item or a block id - so a step "
                                + "naming one is drawn with that thing's own picture.").add()
                        .append(new KeyedCodec<>("Entity", Codec.BOOLEAN, false),
                                (t, v) -> t.entity = v, t -> t.entity)
                        .documentation("A creature, so a step naming one is drawn with that creature's own "
                                + "portrait.").add()
                        .append(new KeyedCodec<>("Currency", Codec.BOOLEAN, false),
                                (t, v) -> t.currency = v, t -> t.currency)
                        .documentation("A wallet, so a step naming one is drawn with that wallet's own icon. "
                                + "Answered by whoever defines currencies, which is why a step earning or "
                                + "spending one needs nothing authored per wallet.").add()
                        .append(new KeyedCodec<>("Content", Codec.BOOLEAN, false),
                                (t, v) -> t.content = v, t -> t.content)
                        .documentation("Another quest or achievement, so a step naming one is drawn with THAT "
                                + "content's own icon - the picture it is already listed under everywhere "
                                + "else.").add()
                        .append(new KeyedCodec<>("Board", Codec.BOOLEAN, false),
                                (t, v) -> t.board = v, t -> t.board)
                        .documentation("A notice board, so a step naming one is drawn with that board's own "
                                + "icon. Answered by whoever defines boards.").add()
                        .append(new KeyedCodec<>("Encounter", Codec.BOOLEAN, false),
                                (t, v) -> t.encounter = v, t -> t.encounter)
                        .metadata(EditorSchema.defaultValue(false))
                        .documentation("A boss fight, named by its encounter script id rather than by the "
                                + "creature standing in for it (a fight that swaps roles between phases has "
                                + "no one creature id). Answered by whoever binds encounters.").add()
                        .build();

        @Nullable private Boolean place;
        @Nullable private Boolean item;
        @Nullable private Boolean entity;
        @Nullable private Boolean currency;
        @Nullable private Boolean content;
        @Nullable private Boolean board;
        @Nullable private Boolean encounter;

        public TargetNames() {
        }

        @Nullable
        public Boolean getPlace() {
            return place;
        }

        @Nullable
        public Boolean getItem() {
            return item;
        }

        @Nullable
        public Boolean getEntity() {
            return entity;
        }

        @Nullable
        public Boolean getCurrency() {
            return currency;
        }

        @Nullable
        public Boolean getContent() {
            return content;
        }

        @Nullable
        public Boolean getBoard() {
            return board;
        }

        @Nullable
        public Boolean getEncounter() {
            return encounter;
        }
    }

    /**
     * How a step of this kind READS and LOOKS: the sentence it renders as, the picture beside it,
     * and the handful of targets that need a picture of their own.
     */
    public static final class Presentation {

        public static final BuilderCodec<Presentation> CODEC =
                BuilderCodec.builder(Presentation.class, Presentation::new)
                        .documentation("How a step of this kind reads and looks.")
                        .append(new KeyedCodec<>("TextKey", Codec.STRING, false),
                                (p, v) -> p.textKey = v, p -> p.textKey)
                        .documentation("The localization key a step of this kind renders through, passed the "
                                + "amount and the target's name. Omit to leave the wording to whoever "
                                + "renders it.").add()
                        .append(new KeyedCodec<>("Icon", IconSpec.CODEC, false),
                                (p, v) -> p.icon = v, p -> p.icon)
                        .documentation("The picture for a step of this kind whose own target has none - one "
                                + "naming a whole family of ids, or naming nothing at all.").add()
                        .append(new KeyedCodec<>("TargetIcons",
                                        new InheritMapCodec<>(IconSpec.CODEC, LinkedHashMap::new), false),
                                (p, v) -> p.targetIcons = v, p -> p.targetIcons)
                        .documentation("A picture for one exact target, keyed by the target id, for a target "
                                + "that has none of its own or wants a different one. A family name whose "
                                + "members each have a portrait but which has none itself belongs here, "
                                + "pointed at whichever member represents it.").add()
                        .build();

        @Nullable private String textKey;
        @Nullable private IconSpec icon;
        @Nullable private Map<String, IconSpec> targetIcons;

        public Presentation() {
        }

        @Nullable
        public String getTextKey() {
            return textKey == null || textKey.isBlank() ? null : textKey;
        }

        @Nullable
        public IconSpec getIcon() {
            return icon;
        }

        @Nonnull
        public Map<String, IconSpec> getTargetIcons() {
            return targetIcons == null ? Map.of() : targetIcons;
        }
    }

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean valueBased;
    @Nullable private Boolean atMost;
    @Nullable private Boolean producible;
    @Nullable private TargetNames targetNames;
    @Nullable private Presentation presentation;

    public static final AssetBuilderCodec<String, ObjectiveKindAsset> CODEC = AssetBuilderCodec.builder(
                    ObjectiveKindAsset.class,
                    ObjectiveKindAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("ValueBased", Codec.BOOLEAN, false),
                    (a, v) -> a.valueBased = v, a -> a.valueBased, (a, p) -> a.valueBased = p.valueBased)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("Whether producers fire the player's CURRENT value rather than an increment, so "
                    + "progress is a high-water mark instead of a running total. Omit to keep whatever the "
                    + "mod that registered this kind said.").add()
            .appendInherited(new KeyedCodec<>("AtMost", Codec.BOOLEAN, false),
                    (a, v) -> a.atMost = v, a -> a.atMost, (a, p) -> a.atMost = p.atMost)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("For a ValueBased kind: read a step's Amount as a CEILING, so the step is met the "
                    + "first time the fired value comes in at or under it (a clear in under so many seconds, "
                    + "a fight with at most so many deaths) and a value over it moves nothing. Progress "
                    + "shows as met or not rather than as a count. Means nothing on a kind that "
                    + "accumulates. Omit to keep whatever the mod that registered this kind said.").add()
            .appendInherited(new KeyedCodec<>("Producible", Codec.BOOLEAN, false),
                    (a, v) -> a.producible = v, a -> a.producible, (a, p) -> a.producible = p.producible)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether content may use this kind at all. Setting false leaves the kind readable "
                    + "but refuses new authoring of it, which is how a vocabulary that has no producer yet "
                    + "says so.").add()
            .appendInherited(new KeyedCodec<>("TargetNames", TargetNames.CODEC, false),
                    (a, v) -> a.targetNames = v, a -> a.targetNames, (a, p) -> a.targetNames = p.targetNames)
            .documentation("What this kind's Target names.").add()
            .appendInherited(new KeyedCodec<>("Presentation", Presentation.CODEC, false),
                    (a, v) -> a.presentation = v, a -> a.presentation,
                    (a, p) -> a.presentation = p.presentation)
            .documentation("How a step of this kind reads and looks.").add()
            .build();

    public ObjectiveKindAsset() {
    }

    /** The kind id this file describes, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public Boolean getValueBased() {
        return valueBased;
    }

    @Nullable
    public Boolean getAtMost() {
        return atMost;
    }

    @Nullable
    public Boolean getProducible() {
        return producible;
    }

    @Nullable
    public TargetNames getTargetNames() {
        return targetNames;
    }

    @Nullable
    public Presentation getPresentation() {
        return presentation;
    }
}
