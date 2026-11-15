package com.ziggfreed.common.asset;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;

/**
 * How the in-game Asset Editor offers one text value: the editor's own picker over an asset type, or
 * a pick list this server serves ({@link EditorDataSets}). A reward kind's parameter and an objective
 * kind's {@code Target} each carry one, so an author picks the item, the block or the creature instead
 * of typing its id.
 *
 * <pre>{@code
 * "Pick": { "Asset": "BlockType" }
 * "Pick": { "List": "ziggfreedcommon:tracks" }
 * }</pre>
 *
 * <p>{@code Asset} names an asset type the way the editor knows it: an engine store's asset class
 * ({@code Item}, {@code BlockType}, {@code EntityEffect}, {@code NPCGroup}, {@code ItemDropList}), an
 * NPC role ({@code NPCRole}), or any store a mod registers ({@code TitleAsset}); it is for a value that must
 * be one asset id (a reward's item). {@code List} names a pick list, offered as suggestions on a text box,
 * so a value the list does not hold (a name pattern, a set key, a word the reader also accepts) can still be
 * typed; {@code ziggfreedcommon:ids/<Type>} lists an asset type's loaded ids ({@link #ids}), the pick for a
 * leaf the matcher reads as a pattern. With both, the asset picker wins.
 *
 * <p><b>Authoring convenience, never validation.</b> Nothing here changes what a file may say or how it
 * decodes; a hand-written value the picker never offered still loads, and the content validators stay
 * the check.
 */
public final class EditorPick {

    /** The asset type whose own picker the editor opens, or null. */
    @Nullable private String asset;
    /** The pick list offered as suggestions, or null. */
    @Nullable private String list;
    /** A closed set offered as a dropdown, Java-side only; null when the value is open. */
    @Nullable private List<String> values;

    public static final BuilderCodec<EditorPick> CODEC = BuilderCodec.builder(EditorPick.class, EditorPick::new)
            .documentation("How the Asset Editor offers this value: the editor's own picker over an asset type, or "
                    + "a pick list this server serves. Convenience only: a value the picker never offered still "
                    + "loads as written.")
            .appendInherited(new KeyedCodec<>("Asset", Codec.STRING, false),
                    (o, v) -> o.asset = v, o -> o.asset, (o, p) -> o.asset = p.asset)
            .documentation("The asset type whose own picker opens, by the editor's name for it: Item, BlockType, "
                    + "EntityEffect, NPCRole, NPCGroup, ItemDropList, ReputationGroup, or a store a mod registers "
                    + "such as TitleAsset. Wins over List when both are written.").add()
            .appendInherited(new KeyedCodec<>("List", Codec.STRING, false),
                    (o, v) -> o.list = v, o -> o.list, (o, p) -> o.list = p.list)
            .documentation("A pick list this server serves, by its id (ziggfreedcommon:tracks, "
                    + "ziggfreedcommon:currencies), offered as suggestions while typing, so a value it does not "
                    + "hold, such as a name pattern, can still be written. ziggfreedcommon:ids/<Type> lists every "
                    + "loaded id of an asset type (ziggfreedcommon:ids/BlockType, ziggfreedcommon:ids/NPCRole).").add()
            .build();

    public EditorPick() {
    }

    /** The editor's own picker over the loaded assets of {@code assetClass}. */
    @Nonnull
    public static EditorPick asset(@Nonnull Class<? extends JsonAsset<?>> assetClass) {
        return asset(assetClass.getSimpleName());
    }

    /**
     * The editor's own picker over an asset type named the way the editor names it: a store's asset
     * class simple name, or a type with no store class of its own here ({@code NPCRole}).
     */
    @Nonnull
    public static EditorPick asset(@Nonnull String assetType) {
        EditorPick pick = new EditorPick();
        pick.asset = assetType;
        return pick;
    }

    /**
     * The loaded ids of {@code assetType} offered as suggestions on a text box ({@link EditorDataSets#assetIds}):
     * the pick for a leaf the matcher reads as a name pattern or a set key, which a strict picker could not take.
     */
    @Nonnull
    public static EditorPick ids(@Nonnull String assetType) {
        return list(EditorDataSets.assetIds(assetType));
    }

    /** As {@link #ids(String)}, for an asset class to hand. */
    @Nonnull
    public static EditorPick ids(@Nonnull Class<? extends JsonAsset<?>> assetClass) {
        return ids(assetClass.getSimpleName());
    }

    /** The pick list {@code dataSet} offers, as suggestions on a text box. */
    @Nonnull
    public static EditorPick list(@Nonnull String dataSet) {
        EditorPick pick = new EditorPick();
        pick.list = dataSet;
        return pick;
    }

    /** A closed set of values, offered as a dropdown: only for a value the reader refuses or ignores otherwise. */
    @Nonnull
    public static EditorPick values(@Nonnull String... values) {
        EditorPick pick = new EditorPick();
        pick.values = List.of(values);
        return pick;
    }

    @Nullable
    public String getAsset() {
        return blankToNull(asset);
    }

    @Nullable
    public String getList() {
        return blankToNull(list);
    }

    /** The closed set, or null when the value is open. */
    @Nullable
    public List<String> getValues() {
        return values;
    }

    /** True when this names nothing to offer. */
    public boolean isEmpty() {
        return getAsset() == null && getList() == null && (values == null || values.isEmpty());
    }

    /**
     * Write this pick onto a text leaf, or onto each entry of a text-array leaf: the asset picker, the
     * suggestion list or the closed dropdown. A no-op on any other shape.
     */
    public void applyTo(@Nonnull Schema schema) {
        String assetType = getAsset();
        if (assetType != null) {
            EditorSchema.assetRef(assetType).modify(schema);
            return;
        }
        if (values != null && !values.isEmpty()) {
            EditorSchema.oneOf(values.toArray(String[]::new)).modify(schema);
            return;
        }
        String dataSet = getList();
        if (dataSet != null) {
            EditorSchema.suggest(dataSet).modify(schema);
        }
    }

    /** A fresh text leaf carrying this pick. */
    @Nonnull
    public StringSchema textLeaf() {
        StringSchema leaf = new StringSchema();
        applyTo(leaf);
        return leaf;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
