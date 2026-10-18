package com.ziggfreed.common.almanac.asset;

import java.util.List;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.ziggfreed.common.ui.page.BannerAsset;
import com.ziggfreed.common.ui.page.PageParts;
import com.ziggfreed.common.ui.page.PageSection;

/**
 * One entry of a season page's {@code Sections}: ONE part of the page's body, named by the one group the
 * entry writes. There is no {@code Type} to pick: the group IS the part. The Almanac's parts are registered on
 * the page kit's part registry under {@link #PAGE} ({@code AlmanacBootstrap.registerParts}), so this entry is
 * read against whatever parts the page has when the file decodes.
 *
 * <pre>{@code
 * "Sections": [ { "Banner": { "Art": "UI/Custom/Almanac/Spring_Fair_Band.png" } },
 *               { "Achievements": {} }, { "Collection": { "Items": [ { "Item": "Spring_Fair_Ribbon" } ] } },
 *               { "Tallies": {} }, { "Keepsakes": {} }, { "Links": {} } ]
 * }</pre>
 *
 * <p>An entry naming two parts draws the first in registration order ({@link #PARTS}) and the page reports it;
 * an entry naming none is skipped, and one naming a group that is no part of the page is skipped with one
 * warning. Only the drawn part's group is handed out ({@link #banner()} and its siblings are null for any
 * other), so a second part written beside it can never draw.
 */
public final class AlmanacSectionAsset extends PageSection {

    /** The page's name on the part registry. */
    public static final String PAGE = "Almanac";

    public static final String BANNER = "Banner";
    public static final String COLLECTION = "Collection";
    public static final String ACHIEVEMENTS = "Achievements";
    public static final String TALLIES = "Tallies";
    public static final String KEEPSAKES = "Keepsakes";
    public static final String LINKS = "Links";

    /** The parts in registration order: the order that picks an entry's drawn part when it names two. */
    public static final List<String> PARTS = List.of(BANNER, COLLECTION, ACHIEVEMENTS, TALLIES, KEEPSAKES, LINKS);

    /** The parts the page draws itself, each at most once: the first place it is written. */
    public static final List<String> BUILT_IN = List.of(ACHIEVEMENTS, TALLIES, KEEPSAKES, LINKS);

    /** One entry, read against the parts registered on {@link #PAGE}. */
    public static final Codec<AlmanacSectionAsset> CODEC =
            PageParts.of(PAGE).sectionCodec(AlmanacSectionAsset.class, AlmanacSectionAsset::new);

    public AlmanacSectionAsset() {
    }

    /** The banner, when it is this entry's drawn part; else null. */
    @Nullable
    public BannerAsset banner() {
        return drawn(BANNER, BannerAsset.class);
    }

    /** The collection, when it is this entry's drawn part; else null. */
    @Nullable
    public AlmanacCollectionAsset collection() {
        return drawn(COLLECTION, AlmanacCollectionAsset.class);
    }

    /** The achievements section, when it is this entry's drawn part; else null. */
    @Nullable
    public AlmanacAchievementsAsset achievements() {
        return drawn(ACHIEVEMENTS, AlmanacAchievementsAsset.class);
    }
}
