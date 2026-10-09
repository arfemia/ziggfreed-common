package com.ziggfreed.common.almanac.asset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *   "Sections": [ { "Banner": { "Text": { "TitleKey": "almanac.spring_fair.banner" } } },
 *                 { "Collection": { "Items": [ { "Item": "Spring_Fair_Ribbon", "Hidden": true } ] } },
 *                 { "Tallies": {} }, { "Achievements": {} } ],
 *   "Stats": { "Kites_Flown": { "Kind": "USE_ITEM", "Target": "Kite_", "MatchMode": "PREFIX" } } }
 * }</pre>
 *
 * <p>The calendar decides whether the season is on; this page only says what the Almanac shows about
 * it. A season's achievements are the ones filed under category {@code Seasons} with this event id as
 * subcategory, so nothing here lists them. {@code Keepsake} names the yearly keepsake's base id: each
 * year mints its own copy, {@code <id>_<year>}, and the Almanac lists every year's copy a player
 * earned. Every leaf is {@code appendInherited} and {@code Stats} merges per line, so a page with a
 * {@code Parent} can retune one line and keep the rest; {@code Hero} merges leaf by leaf except its
 * {@code Composition}, which is one piece ({@link AlmanacHeroAsset}), {@code Links} replaces whole,
 * and {@code Sections} replaces whole ({@link AlmanacSectionAsset}: one part of the body per entry, in
 * the order written; unauthored reads the page's own order).
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

    /** A finding's code: a {@code Sections} entry that names no part, so it is skipped. */
    public static final String FINDING_SECTION_EMPTY = "SECTION_EMPTY";

    /** A finding's code: a {@code Sections} entry naming two or more parts; only the first draws. */
    public static final String FINDING_SECTION_HAS_TWO = "SECTION_HAS_TWO";

    /** A finding's code: a built-in part placed again; it draws at its first place only. */
    public static final String FINDING_SECTION_REPEATED = "SECTION_REPEATED";

    /** A finding's code: more {@code Sections} entries than the page draws. */
    public static final String FINDING_SECTIONS_OVER_CAP = "SECTIONS_OVER_CAP";

    /** A finding's code: a banner that names nothing to show, so it is skipped. */
    public static final String FINDING_BANNER_EMPTY = "BANNER_EMPTY";

    /** A finding's code: more banner items written than a banner draws. */
    public static final String FINDING_BANNER_ITEMS = "BANNER_ITEMS_OVER_CAP";

    /**
     * A finding's code: more slots written in a collection than its grid reads; it counts every slot written, a
     * repeat included, as the grid does.
     */
    public static final String FINDING_COLLECTION_ITEMS_OVER_CAP = "COLLECTION_ITEMS_OVER_CAP";

    /** A finding's code: an item written twice in one collection; it keeps its first place. */
    public static final String FINDING_COLLECTION_ITEM_REPEATED = "COLLECTION_ITEM_REPEATED";

    /** How many {@code Sections} entries a page draws. */
    public static final int SECTIONS_MAX = 16;

    /**
     * How many of a collection's slots its grid reads, in the order written: five rows of nine. A repeat or an item
     * the server lacks among them draws nothing and gives its place to no later slot.
     */
    public static final int COLLECTION_MAX_ITEMS = 45;

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
    @Nullable private AlmanacSectionAsset[] sections;

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
            .appendInherited(new KeyedCodec<>("Sections",
                            new ArrayCodec<>(AlmanacSectionAsset.CODEC, AlmanacSectionAsset[]::new), false),
                    (a, v) -> a.sections = v, a -> a.sections, (a, p) -> a.sections = p.sections)
            .documentation("The season's page below its top, in the order written, one part per entry: Banner, "
                    + "Collection, Achievements, Tallies, Keepsakes or Links. Leave it out for the page's own order "
                    + "(Tallies, Keepsakes, Achievements, Links). A list written by a child or the owner replaces "
                    + "the inherited one whole.")
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
     * The body's parts as authored, in the order written: null when no {@code Sections} is written (the
     * view then reads the page's own order), else every entry the list holds, an empty list staying empty.
     * The entries are read as written; the view caps and checks them.
     */
    @Nullable
    public List<AlmanacSectionAsset> sections() {
        if (sections == null) {
            return null;
        }
        List<AlmanacSectionAsset> out = new ArrayList<>();
        for (AlmanacSectionAsset section : sections) {
            if (section != null) {
                out.add(section);
            }
        }
        return List.copyOf(out);
    }

    /**
     * What is wrong with this page that does not stop it loading, each a warning: a colour that is not
     * {@code #rrggbb} (read as unauthored), a link left out, more hero items than the hero draws; and in
     * the {@code Sections} the page draws, an entry naming no part or two, a built-in part placed again,
     * too many entries, a banner with nothing to show or too many items, a collection repeating an item or
     * listing too many, and a banner's or a collection's button left out.
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
            colourFindings(composition, "Hero.Composition", "the composed top", source, out);
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
        sectionFindings(source, out);
        return List.copyOf(out);
    }

    /** The three colour checks a composition gets, the hero's and every banner's alike. */
    private static void colourFindings(@Nonnull AlmanacHeroAsset.Composition composition, @Nonnull String where,
            @Nonnull String what, @Nonnull String source, @Nonnull List<Finding> out) {
        if (composition.backgroundMalformed()) {
            out.add(Finding.warning(DOMAIN, FINDING_COLOUR, where + ".Background is not a #rrggbb colour, so "
                    + what + " takes the season's accent", source));
        }
        if (composition.gradientMalformed()) {
            out.add(Finding.warning(DOMAIN, FINDING_COLOUR, where + ".Gradient needs a #rrggbb Top and Bottom, so "
                    + "it is not drawn", source));
        }
        if (composition.glowMalformed()) {
            out.add(Finding.warning(DOMAIN, FINDING_COLOUR, where + ".Glow needs a #rrggbb Color, so it is not "
                    + "drawn", source));
        }
    }

    /** The {@code Sections} findings, entry by entry over the entries the page draws, then the cap. */
    private void sectionFindings(@Nonnull String source, @Nonnull List<Finding> out) {
        List<AlmanacSectionAsset> entries = sections();
        if (entries == null) {
            return;
        }
        Set<String> placed = new HashSet<>();
        for (int i = 0; i < entries.size() && i < SECTIONS_MAX; i++) {
            AlmanacSectionAsset entry = entries.get(i);
            String where = "Sections[" + i + "]";
            List<String> written = entry.partsWritten();
            String part = entry.part();
            if (part == null) {
                out.add(Finding.warning(DOMAIN, FINDING_SECTION_EMPTY, where + " names no part, so it is skipped",
                        source));
                continue;
            }
            if (written.size() > 1) {
                out.add(Finding.warning(DOMAIN, FINDING_SECTION_HAS_TWO, where + " names " + spoken(written)
                        + "; one entry draws one part, so only " + part + " is drawn", source));
            }
            if (AlmanacSectionAsset.BUILT_IN.contains(part) && !placed.add(part)) {
                out.add(Finding.warning(DOMAIN, FINDING_SECTION_REPEATED, where + " places " + part + " again, "
                        + "which is drawn at its first place only", source));
            }
            AlmanacBannerAsset banner = entry.banner();
            if (banner != null) {
                bannerFindings(banner, where + ".Banner", source, out);
            }
            AlmanacCollectionAsset collection = entry.collection();
            if (collection != null) {
                collectionFindings(collection, where + ".Collection", source, out);
            }
        }
        if (entries.size() > SECTIONS_MAX) {
            out.add(Finding.warning(DOMAIN, FINDING_SECTIONS_OVER_CAP, "Sections names " + entries.size()
                    + " entries and the page draws the first " + SECTIONS_MAX, source));
        }
    }

    private static void bannerFindings(@Nonnull AlmanacBannerAsset banner, @Nonnull String where,
            @Nonnull String source, @Nonnull List<Finding> out) {
        if (banner.nothingWritten()) {
            out.add(Finding.warning(DOMAIN, FINDING_BANNER_EMPTY, where + " names no Art, Composition, Text or "
                    + "Button, so it shows nothing and is skipped", source));
        }
        AlmanacHeroAsset.Composition composition = banner.composition();
        if (composition != null) {
            colourFindings(composition, where + ".Composition", "the banner", source, out);
            if (composition.items().size() > HERO_MAX_ITEMS) {
                out.add(Finding.warning(DOMAIN, FINDING_BANNER_ITEMS, where + ".Composition.Items names "
                        + composition.items().size() + " items and a banner draws the first " + HERO_MAX_ITEMS,
                        source));
            }
        }
        if (banner.buttonLeftOut()) {
            out.add(Finding.warning(DOMAIN, FINDING_LINK, where + ".Button has no words or opens nothing this "
                    + "server has, so it is left out", source));
        }
    }

    private static void collectionFindings(@Nonnull AlmanacCollectionAsset collection, @Nonnull String where,
            @Nonnull String source, @Nonnull List<Finding> out) {
        List<AlmanacCollectionAsset.Slot> slots = collection.slots();
        Set<String> seen = new HashSet<>();
        for (AlmanacCollectionAsset.Slot slot : slots) {
            if (!seen.add(slot.item().toLowerCase(Locale.ROOT))) {
                out.add(Finding.warning(DOMAIN, FINDING_COLLECTION_ITEM_REPEATED, where + ".Items lists '"
                        + slot.item() + "' again; an item keeps its first place", source));
            }
        }
        if (slots.size() > COLLECTION_MAX_ITEMS) {
            out.add(Finding.warning(DOMAIN, FINDING_COLLECTION_ITEMS_OVER_CAP, where + ".Items names "
                    + slots.size() + " items and the grid reads only the first " + COLLECTION_MAX_ITEMS, source));
        }
        if (collection.buttonLeftOut()) {
            out.add(Finding.warning(DOMAIN, FINDING_LINK, where + ".Button has no words or opens nothing this "
                    + "server has, so it is left out", source));
        }
    }

    /** {@code Banner}, {@code Banner and Tallies}, {@code Banner, Collection and Tallies}. */
    @Nonnull
    private static String spoken(@Nonnull List<String> parts) {
        if (parts.size() < 2) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
