package com.ziggfreed.common.party;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * A pack-authorable, mod-agnostic party-policy asset, loaded from a consumer's
 * {@code Server/<Mod>/Party/*.json} (the path is supplied by the consumer at register
 * time, so common hard-codes no mod name). It carries the policy knobs of a
 * {@link PartyService} - the previously-hardcoded {@code new PartyConfig(4, 60, false)}
 * Java literal lifted into authorable data, resolved {@code defaults < pack < owner}.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors {@code InstancePresetAsset} /
 * Kweebec's {@code RoundPresetAsset} / hyMMO's {@code QuestGiverAsset}). The engine decodes
 * it DIRECTLY into typed fields via {@link #CODEC} - the codec IS the single schema
 * authority on both the pack layer and the owner layer (the same CODEC decodes an owner
 * override). Every {@code KeyedCodec} field name is PascalCase (the constructor rejects a
 * lower-case first letter at static init); the {@code AssetCodecInitTest} unit test guards it.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "default",
 *   "MaxSize": 4, "InviteTimeoutSeconds": 60, "OwnerOnlyInvite": false }
 * }</pre>
 *
 * <ul>
 *   <li>{@code MaxSize} - the most players a party may hold (default 4; clamped &ge;1 in
 *       {@link PartyConfig}).</li>
 *   <li>{@code InviteTimeoutSeconds} - how long a pending invite stays valid before it
 *       auto-expires (default 60; 0 = never expires; clamped &ge;0 in {@link PartyConfig}).</li>
 *   <li>{@code OwnerOnlyInvite} - when true, only the party owner may send invites; when
 *       false, any member may (default false).</li>
 * </ul>
 */
public final class PartySettingsAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, PartySettingsAsset>> {

    /** Sentinel for an absent optional int (use the documented default). */
    static final int UNSET_INT = Integer.MIN_VALUE;

    /** Party-policy defaults (the previously-hardcoded {@code new PartyConfig(4, 60, false)}). */
    private static final int DEFAULT_MAX_SIZE = 4;
    private static final int DEFAULT_INVITE_TIMEOUT_SECONDS = 60;
    private static final boolean DEFAULT_OWNER_ONLY_INVITE = false;

    private String id;
    private AssetExtraInfo.Data data;

    private int maxSize = UNSET_INT;
    private int inviteTimeoutSeconds = UNSET_INT;
    @Nullable private Boolean ownerOnlyInvite;

    public static final AssetBuilderCodec<String, PartySettingsAsset> CODEC = AssetBuilderCodec.builder(
                    PartySettingsAsset.class,
                    PartySettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative
            // key is the filename) - a no-op setter so it doesn't trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("MaxSize", Codec.INTEGER, false), (a, v) -> a.maxSize = v, a -> a.maxSize)
            .add()
            .append(new KeyedCodec<>("InviteTimeoutSeconds", Codec.INTEGER, false), (a, v) -> a.inviteTimeoutSeconds = v, a -> a.inviteTimeoutSeconds)
            .add()
            .append(new KeyedCodec<>("OwnerOnlyInvite", Codec.BOOLEAN, false), (a, v) -> a.ownerOnlyInvite = v, a -> a.ownerOnlyInvite)
            .add()
            .build();

    public PartySettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the normalized runtime {@link PartyConfig}. Any field left absent keeps the
     * documented default, so a partial asset only overrides what it authors. The
     * {@link PartyConfig} canonical constructor clamps the result.
     */
    @Nonnull
    public PartyConfig toConfig() {
        int size = maxSize != UNSET_INT ? maxSize : DEFAULT_MAX_SIZE;
        int timeout = inviteTimeoutSeconds != UNSET_INT ? inviteTimeoutSeconds : DEFAULT_INVITE_TIMEOUT_SECONDS;
        boolean ownerOnly = ownerOnlyInvite != null ? ownerOnlyInvite : DEFAULT_OWNER_ONLY_INVITE;
        return new PartyConfig(size, timeout, ownerOnly);
    }
}
