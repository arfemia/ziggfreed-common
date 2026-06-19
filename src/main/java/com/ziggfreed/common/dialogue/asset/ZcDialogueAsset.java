package com.ziggfreed.common.dialogue.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.AbstractRawJsonAsset;

/**
 * A pack-authorable, mod-agnostic branching NPC dialogue, loaded from a consumer's
 * {@code Server/ZiggfreedCommon/Dialogues/*.json}. Pattern B (raw passthrough): the
 * {@code Payload} carries the dialogue's {@code Start}/{@code Nodes} body, which may
 * declare template extension ({@code extends}/{@code params}/{@code nodeOverrides}/
 * {@code extraNodes}) and option-level sugar - resolution + decode happen in
 * {@link DialogueAssetStore#resolveAll} via the shared
 * {@code DialogueTemplateResolver.resolve} then {@code DialogueEngine.decode}, the
 * common's lift of hyMMO's {@code DialogueConfig.decodeBody}.
 *
 * <p>Beyond the shared raw shape, each dialogue declares an optional {@code Owner} -
 * which game / minigame the entry belongs to - so several consumers can author into
 * one shared store and each resolves only its own dialogues
 * ({@link DialogueAssetStore#resolveAll} filters by owner). All keys are PascalCase
 * (the codec rejects a lower-case first letter at static init).
 *
 * <p>Pack JSON shape (all fields optional except {@code Payload}):
 * <pre>{@code
 * { "Name": "guide", "Owner": "kweebec",
 *   "Payload": { "Start": [...], "Nodes": { ... } } }
 * }</pre>
 */
public final class ZcDialogueAsset extends AbstractRawJsonAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ZcDialogueAsset>> {

    @Nullable private String owner;

    /**
     * Built inline (not via {@link #rawCodec}) so an extra optional top-level
     * {@code Owner} field rides alongside the shared {@code Name} + {@code Payload}.
     * Mirrors {@code AbstractRawJsonAsset.rawCodec}'s builder chain field-for-field,
     * adding only the {@code Owner} capture.
     */
    public static final AssetBuilderCodec<String, ZcDialogueAsset> CODEC = AssetBuilderCodec.builder(
                    ZcDialogueAsset.class,
                    ZcDialogueAsset::new,
                    Codec.STRING,
                    (asset, id) -> asset.id = id,
                    asset -> asset.id,
                    (asset, extra) -> asset.data = extra,
                    asset -> asset.data)
            // Name is an optional human-readable echo of the asset key (Hytale derives the
            // authoritative key from the filename) - a no-op setter so it doesn't trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (asset, name) -> { /* no-op - id already comes from the filename */ },
                    asset -> asset.id)
            .add()
            .append(new KeyedCodec<>("Owner", Codec.STRING, false),
                    (asset, v) -> asset.owner = v, asset -> asset.owner)
            .add()
            .append(new KeyedCodec<>("Payload", Codec.BSON_DOCUMENT, true),
                    (asset, payload) -> asset.payload = payload, asset -> asset.payload)
            .add()
            .build();

    public ZcDialogueAsset() {
    }

    /** Which game / minigame owns this dialogue ({@code null} = unowned, resolves under any filter). */
    @Nullable
    public String getOwner() {
        return owner;
    }
}
