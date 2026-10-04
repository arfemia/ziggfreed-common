package com.ziggfreed.common.almanac.asset;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * One season's page in the Almanac, at {@code Server/ZiggfreedCommon/Almanac/<Owner>/<EventId>.json}.
 * The FILE name is the calendar event id it belongs to; the folder above it is the author's own
 * grouping and never part of the id.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "almanac.hallows_eve.title", "FlavorKey": "almanac.hallows_eve.flavor" },
 *   "Icon": "Jack_Lantern", "Order": 10, "Keepsake": "Hallows_Eve_Keepsake",
 *   "Stats": { "Bombs_Thrown": { "Kind": "USE_ITEM", "Target": "Lantern_Bomb_", "MatchMode": "PREFIX" } } }
 * }</pre>
 *
 * <p>The calendar decides whether the season is on; this page only says what the Almanac shows about
 * it. A season's achievements are the ones filed under category {@code Seasons} with this event id as
 * subcategory, so nothing here lists them. {@code Keepsake} names the yearly keepsake's base id: each
 * year mints its own copy, {@code <id>_<year>}, and the Almanac lists every year's copy a player
 * earned. Every leaf is {@code appendInherited} and {@code Stats} merges per line, so a page with a
 * {@code Parent} can retune one line and keep the rest.
 */
public final class AlmanacEntryAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, AlmanacEntryAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Almanac";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private ContentTextAsset text;
    @Nullable private String icon;
    @Nullable private Integer order;
    @Nullable private String keepsake;
    @Nullable private Map<String, AlmanacStatAsset> stats;

    public static final AssetBuilderCodec<String, AlmanacEntryAsset> CODEC = AssetBuilderCodec.builder(
                    AlmanacEntryAsset.class,
                    AlmanacEntryAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the season is called and a line about it, as localization keys.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
            .documentation("The item whose picture stands beside the season's name at the top of its Almanac page.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Where the season sits in the Almanac's list, lowest first. Unauthored sorts after "
                    + "every season that names one; leave gaps (10, 20, 30).")
            .add()
            .appendInherited(new KeyedCodec<>("Keepsake", Codec.STRING, false),
                    (a, v) -> a.keepsake = v, a -> a.keepsake, (a, p) -> a.keepsake = p.keepsake)
            .documentation("The achievement id of the season's yearly keepsake. Each year mints its own copy, "
                    + "named <id>_<year>, and the Almanac lists every year's copy the player earned. Leave it "
                    + "out for a season with no keepsake.")
            .add()
            .appendInherited(new KeyedCodec<>("Stats", new InheritMapCodec<>(AlmanacStatAsset.CODEC), false),
                    (a, v) -> a.stats = v, a -> a.stats, (a, p) -> a.stats = p.stats)
            .documentation("The tallies the Almanac keeps for this season, keyed by tally name. A child page "
                    + "may retune one line by its key and keeps every line it did not mention.")
            .add()
            .build();

    public AlmanacEntryAsset() {
    }

    /** The calendar event id, lower-cased. */
    @Override
    public String getId() {
        return id;
    }

    /** The key the season's name is authored under, or null. */
    @Nullable
    public String titleKey() {
        return text == null ? null : blankToNull(text.getTitleKey());
    }

    /** The key of the line about the season, or null. */
    @Nullable
    public String flavorKey() {
        return text == null ? null : blankToNull(text.getFlavorKey());
    }

    /** The item pictured for the season, or null. */
    @Nullable
    public String getIcon() {
        return blankToNull(icon);
    }

    /** Sort key among seasons, or {@link Integer#MAX_VALUE} when unauthored. */
    public int orderOrLast() {
        return order == null ? Integer.MAX_VALUE : order;
    }

    /** The keepsake's base achievement id, or null for a season with none. */
    @Nullable
    public String getKeepsake() {
        return blankToNull(keepsake);
    }

    /** The stat lines keyed as authored; never null. */
    @Nonnull
    public Map<String, AlmanacStatAsset> getStats() {
        return stats == null ? Map.of() : Map.copyOf(stats);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
