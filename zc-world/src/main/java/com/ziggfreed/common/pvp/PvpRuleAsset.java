package com.ziggfreed.common.pvp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.world.WorldSelector;

/**
 * PvP rules for the worlds a {@code Where} names, at {@code Server/ZiggfreedCommon/PvpRules/<Id>.json}: how a pack adds
 * a PvP mode (an arena with no bounties, a battleground with a long assist window) by one file, and the thin layer over
 * the native one for what a world's own gameplay config cannot say. The file name is the id.
 *
 * <pre>{@code
 * { "Parent": "Default",
 *   "Where": { "Match": [ "*Arena*" ], "Pvp": true },
 *   "Rules": { "Bounty": { "Enabled": false } } }
 * }</pre>
 *
 * <p>{@code Where} on zc's {@link WorldSelector}, its own {@code Enabled}, and a {@code Rules} group, which is
 * {@link PvpRules} exactly as a gameplay config writes it under {@code Plugin.ZigPvp}. The most specific {@code Where}
 * wins (a gameplay config key, then an exact name, then the longest literal core, then {@code *}), two equally specific
 * keeping the first by id; a world whose own gameplay config writes {@code ZigPvp} reads that and no file here. A file
 * takes every leaf it leaves out from its {@code Parent} (the library's {@code Default}, say), so a mode writes only what
 * it changes. A server owner retunes or switches a file off by id in {@code mods/ziggfreedcommon/pvp-rules.json}, or on
 * the PvP Rules screen of {@code /zigadmin}.
 */
public final class PvpRuleAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PvpRuleAsset>> {

    /** The store's content path. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/PvpRules";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected WorldSelector where;
    @Nullable protected Boolean enabled;
    @Nullable protected PvpRules rules;

    public static final AssetBuilderCodec<String, PvpRuleAsset> CODEC = AssetBuilderCodec.builder(
                    PvpRuleAsset.class,
                    PvpRuleAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Which worlds these rules apply to: world-name patterns (Match), a world's own "
                    + "GameplayConfig key, NPC placements, the world's PvP switch (Pvp) and names to leave out "
                    + "(ExcludeMatch). A world whose own gameplay config writes Plugin.ZigPvp reads that instead. "
                    + "Naming no world applies nowhere.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether these rules apply. Unauthored means true; false keeps the file and applies it "
                    + "nowhere.").add()
            .appendInherited(new KeyedCodec<>("Rules", PvpRules.CODEC, false),
                    (a, v) -> a.rules = v, a -> a.rules, (a, p) -> a.rules = p.rules)
            .documentation("What applies in those worlds, exactly as a gameplay config writes it under "
                    + "Plugin.ZigPvp: Enabled, Credit and Bounty.").add()
            .build();

    public PvpRuleAsset() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static PvpRuleAsset of(@Nonnull String id, @Nullable WorldSelector where, @Nullable Boolean enabled,
            @Nullable PvpRules rules) {
        PvpRuleAsset asset = new PvpRuleAsset();
        asset.id = id;
        asset.where = where;
        asset.enabled = enabled;
        asset.rules = rules;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The worlds named, or null when the file names none (it then applies nowhere). */
    @Nullable
    public WorldSelector where() {
        return where;
    }

    /** True unless switched off. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The rules, or null when the file writes none. */
    @Nullable
    public PvpRules rules() {
        return rules;
    }
}
