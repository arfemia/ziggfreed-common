package com.ziggfreed.common.almanac.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One entry of a season page's {@code Sections}: ONE part of the page's body, named by the one group the
 * entry writes. There is no {@code Type} to pick: the group IS the part.
 *
 * <pre>{@code
 * "Sections": [ { "Banner": { "Art": "UI/Custom/Almanac/Spring_Fair_Band.png" } },
 *               { "Achievements": {} }, { "Collection": { "Items": [ { "Item": "Spring_Fair_Ribbon" } ] } },
 *               { "Tallies": {} }, { "Keepsakes": {} }, { "Links": {} } ]
 * }</pre>
 *
 * <p>An entry naming two parts draws the first in {@link #PARTS} order and the page reports it; an entry
 * naming none is skipped. Only the drawn part's group is handed out ({@link #banner()} and its siblings are
 * null for any other), so a second part written beside it can never draw.
 */
public final class AlmanacSectionAsset {

    public static final String BANNER = "Banner";
    public static final String COLLECTION = "Collection";
    public static final String ACHIEVEMENTS = "Achievements";
    public static final String TALLIES = "Tallies";
    public static final String KEEPSAKES = "Keepsakes";
    public static final String LINKS = "Links";

    /** The parts in declaration order: the order that picks an entry's drawn part when it names two. */
    public static final List<String> PARTS = List.of(BANNER, COLLECTION, ACHIEVEMENTS, TALLIES, KEEPSAKES, LINKS);

    /** The parts the page draws itself, each at most once: the first place it is written. */
    public static final List<String> BUILT_IN = List.of(ACHIEVEMENTS, TALLIES, KEEPSAKES, LINKS);

    @Nullable private AlmanacBannerAsset banner;
    @Nullable private AlmanacCollectionAsset collection;
    @Nullable private AlmanacAchievementsAsset achievements;
    @Nullable private AlmanacPartAsset tallies;
    @Nullable private AlmanacPartAsset keepsakes;
    @Nullable private AlmanacPartAsset links;

    public static final BuilderCodec<AlmanacSectionAsset> CODEC =
            BuilderCodec.builder(AlmanacSectionAsset.class, AlmanacSectionAsset::new)
                    .append(new KeyedCodec<>(BANNER, AlmanacBannerAsset.CODEC, false),
                            (s, v) -> s.banner = v, s -> s.banner)
                    .documentation("An inline banner: a picture or a composition, a title and a line, and a "
                            + "button.").add()
                    .append(new KeyedCodec<>(COLLECTION, AlmanacCollectionAsset.CODEC, false),
                            (s, v) -> s.collection = v, s -> s.collection)
                    .documentation("The items a player can find this season, as a grid; a Hidden one shows '?' "
                            + "until found once.").add()
                    .append(new KeyedCodec<>(ACHIEVEMENTS, AlmanacAchievementsAsset.CODEC, false),
                            (s, v) -> s.achievements = v, s -> s.achievements)
                    .documentation("The season's achievements, their count and feats, and a button into the "
                            + "achievement book opened on this season.").add()
                    .append(new KeyedCodec<>(TALLIES, AlmanacPartAsset.CODEC, false),
                            (s, v) -> s.tallies = v, s -> s.tallies)
                    .documentation("The year chips and the tallies, as the page draws them by default.").add()
                    .append(new KeyedCodec<>(KEEPSAKES, AlmanacPartAsset.CODEC, false),
                            (s, v) -> s.keepsakes = v, s -> s.keepsakes)
                    .documentation("The keepsake shelf, a tile per year.").add()
                    .append(new KeyedCodec<>(LINKS, AlmanacPartAsset.CODEC, false),
                            (s, v) -> s.links = v, s -> s.links)
                    .documentation("The page's Links.").add()
                    .build();

    public AlmanacSectionAsset() {
    }

    /** Every part this entry writes, in {@link #PARTS} order; empty for an entry naming none. */
    @Nonnull
    public List<String> partsWritten() {
        List<String> out = new ArrayList<>();
        if (banner != null) {
            out.add(BANNER);
        }
        if (collection != null) {
            out.add(COLLECTION);
        }
        if (achievements != null) {
            out.add(ACHIEVEMENTS);
        }
        if (tallies != null) {
            out.add(TALLIES);
        }
        if (keepsakes != null) {
            out.add(KEEPSAKES);
        }
        if (links != null) {
            out.add(LINKS);
        }
        return List.copyOf(out);
    }

    /** The part this entry draws: the first it writes, in {@link #PARTS} order; null when it writes none. */
    @Nullable
    public String part() {
        List<String> written = partsWritten();
        return written.isEmpty() ? null : written.get(0);
    }

    /** The banner, when it is this entry's drawn part; else null. */
    @Nullable
    public AlmanacBannerAsset banner() {
        return BANNER.equals(part()) ? banner : null;
    }

    /** The collection, when it is this entry's drawn part; else null. */
    @Nullable
    public AlmanacCollectionAsset collection() {
        return COLLECTION.equals(part()) ? collection : null;
    }

    /** The achievements section, when it is this entry's drawn part; else null. */
    @Nullable
    public AlmanacAchievementsAsset achievements() {
        return ACHIEVEMENTS.equals(part()) ? achievements : null;
    }
}
