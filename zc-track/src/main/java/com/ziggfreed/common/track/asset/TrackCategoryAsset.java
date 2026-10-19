package com.ziggfreed.common.track.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.progress.asset.CategoryPresentationAsset;

/**
 * One category tracks file themselves under, at {@code Server/ZiggfreedCommon/TrackCategories/<Id>.json}: the
 * leaves every category type shares ({@code Order}, {@code Icon}, {@code TitleKey}, {@code Accent}) and the
 * prestige build its tracks default to. The asset id IS the category name, lower-cased at decode, so
 * {@code Gathering.json} describes the category a track writes as {@code "Gathering"}.
 *
 * <pre>{@code
 * { "Order": 10, "Icon": "Tool_Pickaxe_Crude", "TitleKey": "yourmod.track.category.gathering",
 *   "Prestige": { "Build": "Remember" } }
 * }</pre>
 *
 * <p>The library ships Combat ({@code Keep}), Gathering and Crafting ({@code Remember}); a pack's same-id file
 * replaces one by pack order. A category no file describes still works and its tracks default to
 * {@code Keep}.
 */
public final class TrackCategoryAsset extends CategoryPresentationAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackCategoryAsset>> {

    /** The store's content path under a pack's {@code Server/}. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackCategories";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Prestige prestige;
    @Nullable protected TrackAsset.Loadouts loadouts;
    @Nullable protected TrackAsset.Respec respec;

    public static final AssetBuilderCodec<String, TrackCategoryAsset> CODEC = appendLeaves(
            AssetBuilderCodec.builder(
                    TrackCategoryAsset.class,
                    TrackCategoryAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data))
            .appendInherited(new KeyedCodec<>("Prestige", Prestige.CODEC, false),
                    (a, v) -> a.prestige = v, a -> a.prestige, (a, p) -> a.prestige = p.prestige)
            .documentation("What a prestige does by default to the tracks in this category; a track's own "
                    + "Prestige.Build overrides it.").add()
            .appendInherited(new KeyedCodec<>("Loadouts", TrackAsset.Loadouts.CODEC, false),
                    (a, v) -> a.loadouts = v, a -> a.loadouts, (a, p) -> a.loadouts = p.loadouts)
            .documentation("The saved builds this category's tracks default to: how many a player keeps and what "
                    + "a swap costs. A track's own Loadouts leaves override these one by one.").add()
            .appendInherited(new KeyedCodec<>("Respec", TrackAsset.Respec.CODEC, false),
                    (a, v) -> a.respec = v, a -> a.respec, (a, p) -> a.respec = p.respec)
            .documentation("What a respec of this category's tracks costs by default; a track's own Respec.Cost "
                    + "overrides it.").add()
            .build();

    public TrackCategoryAsset() {
    }

    /** The lower-cased category name. */
    @Override
    public String getId() {
        return id;
    }

    /** The build this category's tracks default to, or null when it says none. */
    @Nullable
    public PrestigeBuild prestigeBuild() {
        return prestige == null ? null : PrestigeBuild.of(prestige.build);
    }

    /** The saved-build defaults this category writes, or null. */
    @Nullable
    public TrackAsset.Loadouts loadouts() {
        return loadouts;
    }

    /** The respec default this category writes, or null. */
    @Nullable
    public TrackAsset.Respec respec() {
        return respec;
    }

    /** The category's prestige defaults. */
    public static final class Prestige {

        @Nullable protected String build;

        public static final BuilderCodec<Prestige> CODEC = BuilderCodec.builder(Prestige.class, Prestige::new)
                .appendInherited(new KeyedCodec<>("Build", Codec.STRING, false),
                        (o, v) -> o.build = v, o -> o.build, (o, p) -> o.build = p.build)
                .metadata(PrestigeBuild.editorHint())
                .documentation(PrestigeBuild.DOCUMENTATION + " Unauthored, Keep.").add()
                .build();

        public Prestige() {
        }
    }

    /** Java-side factory for a test; sets the same fields the codec fills. */
    @Nonnull
    public static TrackCategoryAsset of(@Nonnull String id, @Nullable String build) {
        TrackCategoryAsset a = new TrackCategoryAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        if (build != null) {
            a.prestige = new Prestige();
            a.prestige.build = build;
        }
        return a;
    }
}
