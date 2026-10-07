package com.ziggfreed.common.almanac.asset;

import java.util.ArrayList;
import java.util.List;
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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.validation.Finding;

/**
 * One season's page in the Almanac, at {@code Server/ZiggfreedCommon/Almanac/<Owner>/<EventId>.json}.
 * The FILE name is the calendar event id it belongs to; the folder above it is the author's own
 * grouping and never part of the id.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "almanac.spring_fair.title", "FlavorKey": "almanac.spring_fair.flavor" },
 *   "Icon": "Spring_Fair_Ribbon", "Order": 10, "Keepsake": "Spring_Fair_Keepsake", "Accent": "#e8752a",
 *   "Hero": { "Art": "UI/Custom/Almanac/Spring_Fair.png" },
 *   "Links": [ { "TextKey": "almanac.spring_fair.link.book", "Destination": "Achievements" } ],
 *   "Stats": { "Kites_Flown": { "Kind": "USE_ITEM", "Target": "Kite_", "MatchMode": "PREFIX" } } }
 * }</pre>
 *
 * <p>The calendar decides whether the season is on; this page only says what the Almanac shows about
 * it. A season's achievements are the ones filed under category {@code Seasons} with this event id as
 * subcategory, so nothing here lists them. {@code Keepsake} names the yearly keepsake's base id: each
 * year mints its own copy, {@code <id>_<year>}, and the Almanac lists every year's copy a player
 * earned. Every leaf is {@code appendInherited} and {@code Stats} merges per line, so a page with a
 * {@code Parent} can retune one line and keep the rest; {@code Hero} merges leaf by leaf except its
 * {@code Composition}, which is one piece ({@link AlmanacHeroAsset}), and {@code Links} replaces whole.
 *
 * <p>A server owner overrides any of it per season in {@code mods/ziggfreedcommon/almanac.json}
 * ({@link AlmanacOwnerLayers}), decoded against the pack's page by the same rules.
 */
public final class AlmanacEntryAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, AlmanacEntryAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Almanac";

    /** A finding's code: a colour leaf ({@code Accent}, a composition's colours) that is not {@code #rrggbb}. */
    public static final String FINDING_COLOUR = "COLOUR_NOT_HEX";

    /** A finding's code: a link left out, for want of words or of a destination this server can open. */
    public static final String FINDING_LINK = "LINK_LEFT_OUT";

    /** A finding's code: more hero items written than the hero draws. */
    public static final String FINDING_HERO_ITEMS = "HERO_ITEMS_OVER_CAP";

    /** How many items a composed hero draws. */
    public static final int HERO_MAX_ITEMS = 12;

    private static final String DOMAIN = AlmanacValidator.DOMAIN;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private ContentTextAsset text;
    @Nullable private String icon;
    @Nullable private Integer order;
    @Nullable private String keepsake;
    @Nullable private Map<String, AlmanacStatAsset> stats;
    @Nullable private String accent;
    @Nullable private AlmanacHeroAsset hero;
    @Nullable private AlmanacLinkAsset[] links;

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
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands beside the season's name in the Almanac's list, and at "
                    + "the top of its page when no Hero is drawn.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Where the season sits in the Almanac's list, lowest first, after every season on now. "
                    + "Unauthored sorts after every season that names one; leave gaps (10, 20, 30).")
            .add()
            .appendInherited(new KeyedCodec<>("Keepsake", Codec.STRING, false),
                    (a, v) -> a.keepsake = v, a -> a.keepsake, (a, p) -> a.keepsake = p.keepsake)
            .documentation("The achievement id of the season's yearly keepsake. Each year mints its own copy, "
                    + "named <id>_<year>, and the Almanac shows one tile per year: earned, still to earn or "
                    + "missed. Leave it out for a season with no keepsake.")
            .add()
            .appendInherited(new KeyedCodec<>("Stats", new InheritMapCodec<>(AlmanacStatAsset.CODEC), false),
                    (a, v) -> a.stats = v, a -> a.stats, (a, p) -> a.stats = p.stats)
            .documentation("The tallies the Almanac keeps for this season, keyed by tally name. A child page "
                    + "may retune one line by its key and keeps every line it did not mention.")
            .add()
            .appendInherited(new KeyedCodec<>("Accent", Codec.STRING, false),
                    (a, v) -> a.accent = v, a -> a.accent, (a, p) -> a.accent = p.accent)
            .documentation("The season's own colour, #rrggbb, drawn as a thin strip under the top of its page "
                    + "and, darkened, behind a composed top that names no colour. One too dark to read against a "
                    + "row is replaced by the shared accent.")
            .add()
            .appendInherited(new KeyedCodec<>("Hero", AlmanacHeroAsset.CODEC, false),
                    (a, v) -> a.hero = v, a -> a.hero, (a, p) -> a.hero = p.hero)
            .documentation("The top of the season's page: a shipped picture (Art), one composed from pictures "
                    + "the game has (Composition), and the switch between them (ShowArt). Leave it out and the "
                    + "page composes its top from the season's Icon. A server owner may override any leaf per "
                    + "season in mods/ziggfreedcommon/almanac.json; an owner's Composition replaces the pack's whole.")
            .add()
            .appendInherited(new KeyedCodec<>("Links",
                            new ArrayCodec<>(AlmanacLinkAsset.CODEC, AlmanacLinkAsset[]::new), false),
                    (a, v) -> a.links = v, a -> a.links, (a, p) -> a.links = p.links)
            .documentation("Lines at the foot of the season's page that open another screen, each a TextKey and "
                    + "a Destination. A link to a screen no installed mod opens is left out. A list written by a "
                    + "child or the owner replaces the inherited one whole.")
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

    /** The season's colour as a lower-case {@code #rrggbb}, or null when unauthored or not a colour. */
    @Nullable
    public String accent() {
        return AlmanacHeroAsset.hex(accent);
    }

    /** The top of the season's page as authored, or null when no {@code Hero} group is written. */
    @Nullable
    public AlmanacHeroAsset hero() {
        return hero;
    }

    /** The links that have words and somewhere this server can open, in the order written. */
    @Nonnull
    public List<AlmanacLinkAsset> links() {
        if (links == null) {
            return List.of();
        }
        List<AlmanacLinkAsset> out = new ArrayList<>();
        for (AlmanacLinkAsset link : links) {
            if (link != null && link.usable()) {
                out.add(link);
            }
        }
        return List.copyOf(out);
    }

    /**
     * What is wrong with this page that does not stop it loading, each a warning: a colour that is not
     * {@code #rrggbb} (read as unauthored), a link left out, more hero items than the hero draws.
     */
    @Nonnull
    public List<Finding> findings() {
        String source = id == null ? "" : id;
        List<Finding> out = new ArrayList<>();
        if (blankToNull(accent) != null && accent() == null) {
            out.add(Finding.warning(DOMAIN, FINDING_COLOUR, "Accent '" + accent.trim() + "' is not a #rrggbb "
                    + "colour, so the season takes the shared accent", source));
        }
        AlmanacHeroAsset.Composition composition = hero == null ? null : hero.composition();
        if (composition != null) {
            if (composition.backgroundMalformed()) {
                out.add(Finding.warning(DOMAIN, FINDING_COLOUR, "Hero.Composition.Background is not a #rrggbb "
                        + "colour, so the composed top takes the season's accent", source));
            }
            if (composition.gradientMalformed()) {
                out.add(Finding.warning(DOMAIN, FINDING_COLOUR, "Hero.Composition.Gradient needs a #rrggbb Top "
                        + "and Bottom, so it is not drawn", source));
            }
            if (composition.glowMalformed()) {
                out.add(Finding.warning(DOMAIN, FINDING_COLOUR, "Hero.Composition.Glow needs a #rrggbb Color, so "
                        + "it is not drawn", source));
            }
            if (composition.items().size() > HERO_MAX_ITEMS) {
                out.add(Finding.warning(DOMAIN, FINDING_HERO_ITEMS, "Hero.Composition.Items names "
                        + composition.items().size() + " items and the hero draws the first " + HERO_MAX_ITEMS,
                        source));
            }
        }
        if (links != null) {
            for (AlmanacLinkAsset link : links) {
                if (link != null && !link.usable()) {
                    out.add(Finding.warning(DOMAIN, FINDING_LINK, "a link "
                            + (link.textKey() == null ? "with no TextKey" : "'" + link.textKey() + "'")
                            + " has no words or opens nothing this server has, so it is left out", source));
                }
            }
        }
        return List.copyOf(out);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
