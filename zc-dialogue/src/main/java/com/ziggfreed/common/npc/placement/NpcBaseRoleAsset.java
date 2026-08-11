package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;

import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.ziggfreed.common.asset.AbstractRawJsonAsset;

/**
 * A pack-authorable NPC base role a placement's generated {@code Identity} may clone from,
 * loaded from {@code Server/ZiggfreedCommon/NpcBaseRoles/<baseId>.json}. The file's
 * {@code Payload} IS the role's own JSON body in the engine's ordinary NPC-role shape
 * ({@code Type}, {@code Parameters}, its interaction chain and so on) - the asset id is the
 * base-role id an {@code Identity.BaseRole} field names, and {@link NpcRoleGenerator} clones the
 * payload verbatim before substituting the per-placement appearance, name and hint.
 *
 * <p>Pattern B (raw passthrough): a base role authored this way is unmodified Hytale role JSON,
 * so authoring one here is no different from a mod registering a jar resource with {@link
 * NpcRoleGenerator#registerBaseRoleResource} - only the delivery mechanism changes, letting a
 * pack author ship a base role with no Java at all. On every asset fold ({@link
 * NpcBaseRoleConfig}, registered by {@code ../asset/FrameworkAssetRegistrar}) each entry
 * registers into {@link NpcRoleGenerator}'s base-role registry; a base id already registered
 * from Java is OVERWRITTEN (defaults &lt; pack &lt; owner precedence), logged once at INFO naming
 * both. A file with no usable {@code Payload} generates nothing - see {@link NpcBaseRoleValidator}.
 */
public final class NpcBaseRoleAsset extends AbstractRawJsonAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, NpcBaseRoleAsset>> {

    public static final AssetBuilderCodec<String, NpcBaseRoleAsset> CODEC =
            rawCodec(NpcBaseRoleAsset.class, NpcBaseRoleAsset::new);

    public NpcBaseRoleAsset() {
    }

    /** Java-side construction (tests only - a real entry always comes from the codec). */
    @Nonnull
    static NpcBaseRoleAsset of(@Nonnull String id, @Nullable String rawJsonPayload) {
        NpcBaseRoleAsset a = new NpcBaseRoleAsset();
        a.id = id;
        a.payload = rawJsonPayload == null ? null : BsonDocument.parse(rawJsonPayload);
        return a;
    }
}
