package com.ziggfreed.common.track.world;

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
 * Track rules for the worlds a pattern names, at {@code Server/ZiggfreedCommon/TrackWorldRules/<Id>.json}: the
 * thin layer over the native one for what a world's own gameplay config cannot say, such as every world whose name
 * starts {@code Dungeon_}. The file name is the id.
 *
 * <pre>{@code
 * { "Where": { "Match": [ "Dungeon_*" ] },
 *   "Rules": { "Gain": { "Multiplier": 0.5 } } }
 * }</pre>
 *
 * <p>The MMO's World Rules shape, lifted: {@code Where} on zc's {@link WorldSelector}, its own {@code Enabled},
 * and a {@code Rules} group, which is {@link TrackRules} exactly as a gameplay config writes it under
 * {@code Plugin.ZigTrackRules}. The most specific {@code Where} wins (a gameplay config key, then an exact name,
 * then the longest literal core, then {@code *}), two equally specific keeping the first by id; a world whose own
 * gameplay config writes {@code ZigTrackRules} reads that and no file here. A server owner retunes or switches a
 * file off by id in {@code mods/ziggfreedcommon/track-world-rules.json}.
 */
public final class TrackWorldRuleAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackWorldRuleAsset>> {

    /** The store's content path. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackWorldRules";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected WorldSelector where;
    @Nullable protected Boolean enabled;
    @Nullable protected TrackRules rules;

    public static final AssetBuilderCodec<String, TrackWorldRuleAsset> CODEC = AssetBuilderCodec.builder(
                    TrackWorldRuleAsset.class,
                    TrackWorldRuleAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Which worlds these rules apply to: world-name patterns (Match), a world's own "
                    + "GameplayConfig key, NPC placements, and names to leave out (ExcludeMatch). A world whose own "
                    + "gameplay config writes Plugin.ZigTrackRules reads those instead. Naming no world applies "
                    + "nowhere.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether these rules apply. Unauthored means true; false keeps the file and applies it "
                    + "nowhere.").add()
            .appendInherited(new KeyedCodec<>("Rules", TrackRules.CODEC, false),
                    (a, v) -> a.rules = v, a -> a.rules, (a, p) -> a.rules = p.rules)
            .documentation("What applies in those worlds, exactly as a gameplay config writes it under "
                    + "Plugin.ZigTrackRules: Gain and Grants.").add()
            .build();

    public TrackWorldRuleAsset() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static TrackWorldRuleAsset of(@Nonnull String id, @Nullable WorldSelector where, @Nullable Boolean enabled,
            @Nullable TrackRules rules) {
        TrackWorldRuleAsset asset = new TrackWorldRuleAsset();
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
    public TrackRules rules() {
        return rules;
    }
}
