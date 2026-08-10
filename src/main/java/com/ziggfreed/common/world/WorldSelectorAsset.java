package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * Gives a set of worlds a NAME, so content can say "stand here" / "apply here" without repeating
 * a match pattern in every file.
 *
 * <p><b>A world selector is a named, reusable MATCHER - a shorthand for match patterns - not an
 * opaque tag.</b> A name resolves back to the pattern that matched, so it carries that pattern's
 * specificity and a rule targeting a name sorts on the same ladder as a rule written inline (see
 * {@link MatchRank}). A tag would be a new concept with no intrinsic specificity, and two rules
 * pointing at one world could not be ordered.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/WorldSelectors/<id>.json} (Pattern A - this codec
 * IS the schema; the engine decodes straight into typed fields):
 * <pre>{@code
 * { "Names":          ["forgotten_temple", "instance"],
 *   "Match":          ["*Forgotten_Temple*"],
 *   "GameplayConfig": ["ForgottenTemple"] }
 * }</pre>
 *
 * <ul>
 *   <li><b>{@code Names} is REQUIRED</b> - it is the whole contribution. An absent or empty list
 *       is a validation error; there is no filename fallback.</li>
 *   <li><b>The asset id (the filename) is a pure ADDRESS</b>, used for owner overrides and for
 *       native {@code Parent} inheritance, and is NEVER semantic. That is what makes names
 *       many-to-many and collision-safe: two mods can each ship a file contributing patterns to
 *       {@code primary} and BOTH apply, because a world's names are the union across every
 *       matching asset. Prefix the filename with your mod ({@code Mmo_}, {@code Zc_}) so the two
 *       files address different slots.</li>
 *   <li><b>{@code Match}</b> takes the world-name grammar of {@link WorldNameMatcher}:
 *       {@code Foo} (exact), {@code Foo*} (prefix), {@code *Foo} (suffix), {@code *Foo*}
 *       (contains), {@code *} (everything). Only {@code *Foo*} reaches a live instance world,
 *       which is named {@code instance-<Name>-<random uuid>}.</li>
 *   <li><b>{@code GameplayConfig}</b> matches the world's own authored {@code GameplayConfig}
 *       key exactly. It has no uuid in it and it is stable across instance re-creation, so it is
 *       the sturdiest way to name an instance world - and it ranks above everything else.</li>
 * </ul>
 *
 * <p>To re-point a name at different worlds, override the file by id (drop a same-named file in a
 * later pack, or in the owner layer); to ADD worlds to a name, ship your own file listing the same
 * name. Both work without touching the original.
 *
 * <p>Every leaf is registered with {@code appendInherited}, so a file carrying
 * {@code "Parent": "<id>"} that overrides only {@code Match} still inherits {@code Names} and
 * {@code GameplayConfig} instead of silently losing them.
 *
 * <p>{@code Tags} is a reserved engine key (the asset codec attaches its own), so it is not used
 * here. {@code ExcludeNames} is deliberately absent too: an asset that CONTRIBUTES a name cannot
 * also filter on names without making the resolution order between assets significant. Exclusion
 * belongs on the consuming side, where {@link WorldSelector} carries it.
 */
public final class WorldSelectorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, WorldSelectorAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String[] names;
    @Nullable private String[] match;
    @Nullable private String[] gameplayConfig;

    public static final AssetBuilderCodec<String, WorldSelectorAsset> CODEC = AssetBuilderCodec.builder(
                    WorldSelectorAsset.class,
                    WorldSelectorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Names", Codec.STRING_ARRAY, false),
                    (a, v) -> a.names = v, a -> a.names, (a, p) -> a.names = p.names)
            .add()
            .appendInherited(new KeyedCodec<>("Match", Codec.STRING_ARRAY, false),
                    (a, v) -> a.match = v, a -> a.match, (a, p) -> a.match = p.match)
            .add()
            .appendInherited(new KeyedCodec<>("GameplayConfig", Codec.STRING_ARRAY, false),
                    (a, v) -> a.gameplayConfig = v, a -> a.gameplayConfig,
                    (a, p) -> a.gameplayConfig = p.gameplayConfig)
            .add()
            .build();

    public WorldSelectorAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public String[] getNames() {
        return names;
    }

    @Nullable
    public String[] getMatch() {
        return match;
    }

    @Nullable
    public String[] getGameplayConfig() {
        return gameplayConfig;
    }

    /** Build the resolved runtime model. {@code assetId} is the map key (the filename). */
    @Nonnull
    public WorldSelectorDef toDef(@Nonnull String assetId) {
        return new WorldSelectorDef(assetId, names, match, gameplayConfig);
    }
}
