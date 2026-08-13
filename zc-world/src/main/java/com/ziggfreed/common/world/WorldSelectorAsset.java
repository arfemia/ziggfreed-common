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
 *   "GameplayConfig": ["ForgottenTemple"],
 *   "ExcludeNames":   ["arena"] }
 * }</pre>
 *
 * <ul>
 *   <li><b>{@code Names} is REQUIRED</b> - it is the whole contribution. An absent or empty list
 *       is a validation error; there is no filename fallback.</li>
 *   <li><b>The asset id (the filename) is a pure ADDRESS</b>, used for owner overrides and for
 *       native {@code Parent} inheritance, and is NEVER semantic. That is what makes names
 *       many-to-many and collision-safe: two mods can each ship a file contributing patterns to
 *       one name and BOTH apply, because a world's names are the union across every matching
 *       asset. Prefix the filename with your mod ({@code Mmo_}, {@code Zc_}) so the two files
 *       address different slots.</li>
 *   <li><b>{@code Match}</b> takes the world-name grammar of {@link WorldNameMatcher}:
 *       {@code Foo} (exact), {@code Foo*} (prefix), {@code *Foo} (suffix), {@code *Foo*}
 *       (contains), {@code *} (everything). Only {@code *Foo*} reaches a live instance world,
 *       which is named {@code instance-<Name>-<random uuid>}.</li>
 *   <li><b>{@code GameplayConfig}</b> matches the world's own authored {@code GameplayConfig}
 *       key exactly. It has no uuid in it and it is stable across instance re-creation, so it is
 *       the sturdiest way to name an instance world - and it ranks above everything else.</li>
 *   <li><b>{@code ExcludeNames}</b> withdraws this file's contribution from a world that already
 *       carries one of the listed names, so "every world except the arenas" is one file rather
 *       than a pattern that has to enumerate the exceptions. It is a FILTER over the positive
 *       axes, never a complement of them: a file with only {@code ExcludeNames} contributes
 *       nothing anywhere, because it never said which worlds it applies to in the first place.
 *       <b>Exclusion is resolved against the names a world earns from every file's POSITIVE
 *       axes, worked out first</b>, so two files excluding each other's names give the same
 *       answer whatever order the packs happen to load in.</li>
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
 * here.
 */
public final class WorldSelectorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, WorldSelectorAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String[] names;
    @Nullable private String[] match;
    @Nullable private String[] gameplayConfig;
    @Nullable private String[] excludeNames;

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
            .documentation("The selector names this file hands out. Required: the name IS the "
                    + "contribution, and the filename is only an address. Several files may hand out "
                    + "the same name and all of them apply.")
            .add()
            .appendInherited(new KeyedCodec<>("Match", Codec.STRING_ARRAY, false),
                    (a, v) -> a.match = v, a -> a.match, (a, p) -> a.match = p.match)
            .documentation("World-name patterns a world must satisfy to earn the names: Foo (exact), "
                    + "Foo* (prefix), *Foo (suffix), *Foo* (contains) or * (every world). Only the "
                    + "*Foo* form reaches an instance world, whose name carries a random uuid.")
            .add()
            .appendInherited(new KeyedCodec<>("GameplayConfig", Codec.STRING_ARRAY, false),
                    (a, v) -> a.gameplayConfig = v, a -> a.gameplayConfig,
                    (a, p) -> a.gameplayConfig = p.gameplayConfig)
            .documentation("Exact matches against a world's own authored GameplayConfig key. It has no "
                    + "uuid in it and survives an instance being rebuilt, so it is the sturdiest way "
                    + "to name an instance world, and it outranks every name pattern.")
            .add()
            .appendInherited(new KeyedCodec<>("ExcludeNames", Codec.STRING_ARRAY, false),
                    (a, v) -> a.excludeNames = v, a -> a.excludeNames,
                    (a, p) -> a.excludeNames = p.excludeNames)
            .documentation("Withdraw this file's names from any world that already carries one of "
                    + "these names, for 'everywhere except' without listing the exceptions as "
                    + "patterns. It filters the patterns above rather than standing in for them, so a "
                    + "file with only ExcludeNames hands out nothing anywhere.")
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

    @Nullable
    public String[] getExcludeNames() {
        return excludeNames;
    }

    /** Build the resolved runtime model. {@code assetId} is the map key (the filename). */
    @Nonnull
    public WorldSelectorDef toDef(@Nonnull String assetId) {
        return new WorldSelectorDef(assetId, names, match, gameplayConfig, excludeNames);
    }
}
