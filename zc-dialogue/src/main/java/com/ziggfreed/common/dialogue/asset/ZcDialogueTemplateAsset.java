package com.ziggfreed.common.dialogue.asset;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.ziggfreed.common.asset.AbstractRawJsonAsset;

/**
 * A reusable, mod-agnostic dialogue skeleton - pack content that concrete dialogues
 * pull from via top-level {@code "extends": "<template-id>"}. Loaded from a consumer's
 * {@code Server/ZiggfreedCommon/DialogueTemplates/*.json}. Templates load BEFORE the
 * dialogues store (the dialogues store {@code loadsAfter} this type) so {@code extends}
 * resolves at decode time.
 *
 * <p>Pattern B (raw passthrough), the plain {@code Name} + {@code Payload} shape (no
 * {@code Owner}; templates are owner-agnostic skeletons). The {@code Payload} mirrors a
 * normal dialogue body ({@code Start} + {@code Nodes}) with {@code {{paramName}}}
 * substitution tokens that resolving dialogues fill via their {@code params} map -
 * {@code DialogueTemplateResolver.resolve} performs the merge.
 */
public final class ZcDialogueTemplateAsset extends AbstractRawJsonAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ZcDialogueTemplateAsset>> {

    public static final AssetBuilderCodec<String, ZcDialogueTemplateAsset> CODEC =
            rawCodec(ZcDialogueTemplateAsset.class, ZcDialogueTemplateAsset::new);

    public ZcDialogueTemplateAsset() {
    }
}
