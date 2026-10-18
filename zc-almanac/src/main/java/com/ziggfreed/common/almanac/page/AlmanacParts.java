package com.ziggfreed.common.almanac.page;

import java.util.List;

import com.ziggfreed.common.almanac.asset.AlmanacAchievementsAsset;
import com.ziggfreed.common.almanac.asset.AlmanacCollectionAsset;
import com.ziggfreed.common.almanac.asset.AlmanacPartAsset;
import com.ziggfreed.common.almanac.asset.AlmanacSectionAsset;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.AchievementsPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.BannerPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.CollectionPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.KeepsakesPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.LinksPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.TalliesPlan;
import com.ziggfreed.common.ui.page.BannerAsset;
import com.ziggfreed.common.ui.page.PagePart;

/**
 * The season page's six parts on the page kit: each the group an author writes in a page's {@code Sections}, the
 * codec that reads it, and the template and painter that draw it ({@link AlmanacPage}). {@code AlmanacBootstrap}
 * registers {@link #ALL} on the kit's registry under {@link AlmanacSectionAsset#PAGE}, in that order, before any
 * page decodes; a section's plan names its part, so the page paints each through the kit.
 */
public final class AlmanacParts {

    public static final PagePart<?, ?> BANNER = PagePart.named(AlmanacSectionAsset.BANNER, BannerAsset.CODEC)
            .documentation("An inline banner: a picture or a composition, a title and a line, and a button.")
            .paints(BannerPlan.class, AlmanacPage.BANNER_TEMPLATE, AlmanacPage::paintPlate);

    public static final PagePart<?, ?> COLLECTION =
            PagePart.named(AlmanacSectionAsset.COLLECTION, AlmanacCollectionAsset.CODEC)
                    .documentation("The items a player can find this season, as a grid; a Hidden one shows '?' "
                            + "until found once.")
                    .paints(CollectionPlan.class, AlmanacPage.COLLECTION_TEMPLATE, AlmanacPage::paintCollection);

    public static final PagePart<?, ?> ACHIEVEMENTS =
            PagePart.named(AlmanacSectionAsset.ACHIEVEMENTS, AlmanacAchievementsAsset.CODEC)
                    .documentation("The season's achievements, their count and feats, and a button into the "
                            + "achievement book opened on this season.")
                    .paints(AchievementsPlan.class, AlmanacPage.ACHIEVEMENTS_TEMPLATE, AlmanacPage::paintAchievements);

    public static final PagePart<?, ?> TALLIES = PagePart.named(AlmanacSectionAsset.TALLIES, AlmanacPartAsset.CODEC)
            .documentation("The year chips and the tallies, as the page draws them by default.")
            .paints(TalliesPlan.class, AlmanacPage.TALLIES_TEMPLATE, AlmanacPage::paintTallies);

    public static final PagePart<?, ?> KEEPSAKES =
            PagePart.named(AlmanacSectionAsset.KEEPSAKES, AlmanacPartAsset.CODEC)
                    .documentation("The keepsake shelf, a tile per year.")
                    .paints(KeepsakesPlan.class, AlmanacPage.KEEPSAKES_TEMPLATE, AlmanacPage::paintKeepsakes);

    public static final PagePart<?, ?> LINKS = PagePart.named(AlmanacSectionAsset.LINKS, AlmanacPartAsset.CODEC)
            .documentation("The page's Links.")
            .paints(LinksPlan.class, AlmanacPage.LINKS_TEMPLATE, AlmanacPage::paintLinks);

    /** Every part, in registration order: {@link AlmanacSectionAsset#PARTS}' order, which picks an entry's drawn part. */
    public static final List<PagePart<?, ?>> ALL = List.of(BANNER, COLLECTION, ACHIEVEMENTS, TALLIES, KEEPSAKES, LINKS);

    private AlmanacParts() {
    }
}
