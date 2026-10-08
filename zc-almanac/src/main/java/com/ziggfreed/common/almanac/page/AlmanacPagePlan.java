package com.ziggfreed.common.almanac.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.view.AlmanacLines;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.almanac.view.AlmanacView.Banner;
import com.ziggfreed.common.almanac.view.AlmanacView.Feat;
import com.ziggfreed.common.almanac.view.AlmanacView.Hero;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroComposition;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGlow;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroItem;
import com.ziggfreed.common.almanac.view.AlmanacView.MonthMarks;
import com.ziggfreed.common.almanac.view.AlmanacView.Record;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonAchievements;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonLink;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonPage;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.almanac.view.AlmanacView.YearChip;
import com.ziggfreed.common.almanac.view.AlmanacView.YearKeepsake;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.KeepsakeState;
import com.ziggfreed.common.ui.kit.KeepsakeTile;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.StatTile;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.route.Destination;

/**
 * What the Almanac page shows, as the kit's records and a few flags: the season list as a ledger (On now, then
 * All seasons), the banner card, the year at a glance, the record card, and the season being read (its hero,
 * year chips, scope, tiles, keepsake shelf, achievements and links), or the empty state when no season is
 * listed. Pure: {@link AlmanacView} decides what a season says (which years, which scope, what is earned) and
 * {@link AlmanacLines} how it reads; this maps both onto what the kit paints, so a test can read every choice
 * the page would otherwise hide. {@link AlmanacPage} paints it and decides nothing.
 */
record AlmanacPagePlan(@Nonnull LedgerModel seasons, @Nullable String selected, @Nullable BannerCard banner,
        @Nonnull RecordCard record, @Nonnull List<GlanceMonth> months, @Nullable SeasonBody body,
        @Nonnull EmptyState empty) {

    /** The season list's two sections: the seasons on now, then every other. */
    static final String LIVE_SECTION = "live";
    static final String ALL_SECTION = "all";

    /** The empty state's picture: the Almanac's own (the rail tab's). */
    static final String EMPTY_PICTURE = "Deco_Scroll";

    /** A section never cuts its seasons short: the page has no "Show more" (every pick reopens it). */
    private static final int LIST_CAP = 1000;

    /** Which top a season's page draws: shipped art, a hero composed from item pictures, or its own picture. */
    enum HeroKind { ART, COMPOSED, PICTURE }

    /** A box on the 962 x 240 plate, by its top-left corner. */
    record HeroBox(int x, int y, int width, int height) {
    }

    /** One item picture of a composed hero: its own icon texture, where it sits, how big. */
    record HeroPicture(@Nonnull String itemId, @Nonnull String iconPath, @Nonnull HeroBox box) {
    }

    /** A composed hero's glow: its tint and its box, fitted onto the plate. */
    record HeroLight(@Nonnull String colorHex, @Nonnull HeroBox box) {
    }

    /**
     * The hero: its kind; the texture its art layer draws ({@code artTexture}: the art, or a composition's
     * background texture; null hides the layer, so the plate behind shows); the flat fill over the plate
     * ({@code fillHex}); the tint of the white sky ({@code skyHex}, null hides it); the glow; the composed
     * pictures; the season's own picture ({@code pictureTexture}, the {@code PICTURE} kind only); the chip, the
     * name, the dates line; and the accent strip's colour, clamped.
     */
    record HeroPlan(@Nonnull HeroKind kind, @Nullable String artTexture, @Nullable String fillHex,
            @Nullable String skyHex, @Nullable HeroLight glow, @Nonnull List<HeroPicture> pictures,
            @Nullable String pictureTexture, @Nonnull Pill chip, @Nonnull Message title, @Nullable Message dates,
            @Nonnull String accentHex) {
    }

    /** One year chip: its words, what its click carries ({@code Year}), and its check and dot. */
    record YearChoice(@Nonnull Message label, @Nonnull String value, boolean on, boolean check, boolean dot) {
    }

    /** The keepsake shelf: "1 of 2 years" and a tile per year. */
    record KeepsakeShelf(@Nonnull Message meta, @Nonnull List<KeepsakeTile> tiles) {
    }

    /** The achievements section: "4 of 9 earned" and its bar (both absent with nothing listed), and the feats. */
    record AchievementShelf(@Nullable Message meta, @Nullable Float fraction, @Nonnull List<Pill> feats) {
    }

    /** One link button: its words and where it opens. */
    record LinkButton(@Nonnull Message label, @Nonnull Destination destination) {
    }

    /** The season being read: everything the right column shows. */
    record SeasonBody(@Nonnull String eventId, @Nonnull HeroPlan hero, @Nullable Message flavor,
            @Nonnull List<YearChoice> years, @Nonnull Message scopeHeader, @Nonnull Message scopeMeta,
            @Nonnull List<StatTile> tiles, boolean hint, @Nullable KeepsakeShelf keepsakes,
            @Nullable AchievementShelf achievements, @Nonnull List<LinkButton> links) {
    }

    /** The cross-season banner card: its picture, its name, its count and bar (absent when it has none). */
    record BannerCard(@Nonnull Picture picture, @Nonnull Message name, @Nullable Message meta,
            @Nullable Float fraction) {
    }

    /** The record card: seasons taken part in and keepsakes earned, each a figure over its words. */
    record RecordCard(@Nonnull Message seasonsFigure, @Nonnull Message seasonsCaption,
            @Nonnull Message keepsakesFigure, @Nonnull Message keepsakesCaption) {
    }

    /**
     * One month row of the year at a glance: its short name, whether it is this month, one mark per season
     * running in it (each the season's accent, clamped), and the season a click on it opens (null: none).
     */
    record GlanceMonth(int month, @Nonnull Message label, boolean current, @Nonnull List<String> markHexes,
            @Nullable String firstEventId) {
    }

    /**
     * The whole page.
     *
     * @param seasons      every listed season, On now first ({@link AlmanacView#seasons})
     * @param timings      each listed season's timing, by event id (a missing one reads On now or Between seasons)
     * @param page         the season being read, or null when no season is listed
     * @param months       the year at a glance ({@link AlmanacView#yearAtAGlance})
     * @param accents      each season's authored accent, by event id (null or missing: the kit's gold)
     * @param currentMonth today's month, 1 to 12
     * @param banner       the cross-season achievement, or null while none is in circulation
     * @param bannerIcon   its picture's item id, or null
     */
    @Nonnull
    static AlmanacPagePlan of(@Nonnull List<Season> seasons, @Nonnull Map<String, Timing> timings,
            @Nullable SeasonPage page, @Nonnull Record record, @Nonnull List<MonthMarks> months,
            @Nonnull Map<String, String> accents, int currentMonth, @Nullable Banner banner,
            @Nullable String bannerIcon) {
        return new AlmanacPagePlan(list(seasons, timings), page == null ? null : page.season().eventId(),
                banner(banner, bannerIcon), record(record), glance(months, accents, currentMonth),
                page == null ? null : body(page), new EmptyState(Picture.item(EMPTY_PICTURE),
                        AlmanacText.line("empty.none.title"), AlmanacText.line("empty.none.line"), null));
    }

    /** A season's tone, on its row's bar and its chip's dot: on now, returning within 14 days, or neither. */
    @Nonnull
    static Tone tone(@Nonnull Timing timing) {
        return timing.live() ? Tone.LIVE : timing.soon() ? Tone.AVAILABLE : Tone.BLOCKED;
    }

    // ---- the left column ----

    @Nonnull
    private static LedgerModel list(@Nonnull List<Season> seasons, @Nonnull Map<String, Timing> timings) {
        List<LedgerRow> live = new ArrayList<>();
        List<LedgerRow> rest = new ArrayList<>();
        for (Season season : seasons) {
            Timing timing = timings.get(season.eventId());
            if (timing == null) {
                timing = new Timing(season.live(), null, false, null, false, null, null, null, false);
            }
            LedgerRow row = new LedgerRow(season.eventId(), AlmanacText.authored(season.titleKey(), season.eventId()),
                    AlmanacLines.chip(timing), Picture.item(season.icon()), tone(timing), null, null, null,
                    Mark.NONE, false);
            (season.live() ? live : rest).add(row);
        }
        List<LedgerSection> sections = new ArrayList<>();
        if (!live.isEmpty()) {
            sections.add(new LedgerSection(LIVE_SECTION, AlmanacText.line("status.live"), live, true, LIST_CAP));
        }
        if (!rest.isEmpty()) {
            sections.add(new LedgerSection(ALL_SECTION, AlmanacText.line("seasons.all"), rest, true, LIST_CAP));
        }
        return LedgerModel.of(sections);
    }

    @Nullable
    private static BannerCard banner(@Nullable Banner banner, @Nullable String icon) {
        if (banner == null) {
            return null;
        }
        Message name = ProgressionTexts.titleOrUntitled(banner.achievementId());
        int total = banner.childrenTotal();
        if (total > 0) {
            int earned = Math.min(banner.childrenEarned(), total);
            return new BannerCard(Picture.item(icon), name,
                    AlmanacText.line("achievements.count", (long) earned, (long) total), (float) earned / total);
        }
        return new BannerCard(Picture.item(icon), name, null, banner.earned() ? 1f : null);
    }

    @Nonnull
    private static RecordCard record(@Nonnull Record record) {
        return new RecordCard(Msg.num(record.seasonsTakenPart()), AlmanacLines.recordSeasons(record.seasonsTakenPart()),
                Msg.num(record.keepsakes()), AlmanacLines.recordKeepsakes(record.keepsakes()));
    }

    @Nonnull
    private static List<GlanceMonth> glance(@Nonnull List<MonthMarks> months, @Nonnull Map<String, String> accents,
            int currentMonth) {
        List<GlanceMonth> out = new ArrayList<>();
        for (int month = 1; month <= AlmanacLayout.MONTHS; month++) {
            List<String> ids = List.of();
            for (MonthMarks marks : months) {
                if (marks.month() == month) {
                    ids = marks.eventIds();
                    break;
                }
            }
            List<String> hexes = new ArrayList<>();
            for (String id : ids) {
                if (hexes.size() >= AlmanacLayout.MONTH_MARKS_MAX) {
                    break;
                }
                hexes.add(ZigTokens.clampAccent(accents.get(id)));
            }
            out.add(new GlanceMonth(month, AlmanacLines.shortMonth(month), month == currentMonth, List.copyOf(hexes),
                    ids.isEmpty() ? null : ids.get(0)));
        }
        return List.copyOf(out);
    }

    // ---- the season being read ----

    @Nonnull
    private static SeasonBody body(@Nonnull SeasonPage page) {
        Season season = page.season();
        Scope scope = page.scope();
        long yearsTakenPart = 0L;
        Integer liveYear = null;
        for (YearChip chip : page.years()) {
            if (chip.tookPart()) {
                yearsTakenPart++;
            }
            if (chip.live()) {
                liveYear = chip.year();
            }
        }
        Message flavor = season.flavorKey() == null ? null : AlmanacText.authored(season.flavorKey(), season.eventId());
        // The first-time hint promises tallies, so a page that counts nothing never shows it.
        boolean hint = yearsTakenPart == 0L && !page.tallies().isEmpty();
        return new SeasonBody(season.eventId(), hero(page), flavor, years(page.years(), scope),
                AlmanacLines.scopeHeader(scope), AlmanacLines.scopeMeta(scope, page.tookPartInScope(), yearsTakenPart),
                tiles(page.tallies()), hint, keepsakes(page.keepsakes(), liveYear),
                achievements(page.achievements()), links(page.links()));
    }

    @Nonnull
    private static List<YearChoice> years(@Nonnull List<YearChip> chips, @Nonnull Scope scope) {
        if (chips.isEmpty()) {
            return List.of();
        }
        List<YearChoice> out = new ArrayList<>();
        for (YearChip chip : chips) {
            String year = String.valueOf(chip.year());
            // A year is a label, never a quantity: passed as text so no locale groups it.
            out.add(new YearChoice(AlmanacText.line("year", year), year,
                    !scope.every() && scope.year() == chip.year(), chip.tookPart(), chip.live()));
        }
        out.add(new YearChoice(AlmanacText.line("scope.every"), AlmanacEventData.EVERY, scope.every(), false, false));
        return List.copyOf(out);
    }

    @Nonnull
    private static List<StatTile> tiles(@Nonnull List<Tally> tallies) {
        List<StatTile> out = new ArrayList<>();
        for (Tally tally : tallies) {
            out.add(new StatTile(tally.statId(), Picture.item(tally.icon()), Msg.num(tally.figure()),
                    AlmanacText.authored(tally.textKey(), tally.statId()), AlmanacLines.tileCaption(tally),
                    AlmanacLines.tileServer(tally), tally.figure() == 0L));
        }
        return List.copyOf(out);
    }

    @Nullable
    private static KeepsakeShelf keepsakes(@Nullable List<YearKeepsake> keepsakes, @Nullable Integer liveYear) {
        if (keepsakes == null || keepsakes.isEmpty()) {
            return null;
        }
        List<KeepsakeTile> tiles = new ArrayList<>();
        long earned = 0L;
        for (YearKeepsake keepsake : keepsakes) {
            String id = keepsake.achievementId();
            Message stateLine = switch (keepsake.state()) {
                case EARNED -> id == null ? AlmanacText.line("keepsakes.title") : ProgressionTexts.titleOrUntitled(id);
                case TO_EARN -> AlmanacText.line("keepsake.to_earn");
                case MISSED -> AlmanacText.line("keepsake.missed");
            };
            if (keepsake.state() == KeepsakeState.EARNED) {
                earned++;
            }
            String year = String.valueOf(keepsake.year());
            tiles.add(new KeepsakeTile("k" + year, AlmanacText.line("year", year), stateLine,
                    Picture.item(keepsake.icon()), keepsake.state(),
                    liveYear != null && keepsake.year() == liveYear, id == null ? null : ProgressionTexts.flavor(id)));
        }
        return new KeepsakeShelf(AlmanacLines.keepsakesMeta(earned, tiles.size()), List.copyOf(tiles));
    }

    @Nullable
    private static AchievementShelf achievements(@Nullable SeasonAchievements achievements) {
        if (achievements == null) {
            return null;
        }
        List<Pill> feats = new ArrayList<>();
        for (Feat feat : achievements.feats()) {
            feats.add(Pill.of(ProgressionTexts.titleOrUntitled(feat.achievementId()), Tone.DONE));
        }
        int total = achievements.total();
        if (total <= 0) {
            return new AchievementShelf(null, null, List.copyOf(feats));
        }
        int earned = Math.min(achievements.earned(), total);
        return new AchievementShelf(AlmanacText.line("achievements.count", (long) earned, (long) total),
                (float) earned / total, List.copyOf(feats));
    }

    @Nonnull
    private static List<LinkButton> links(@Nonnull List<SeasonLink> links) {
        List<LinkButton> out = new ArrayList<>();
        for (SeasonLink link : links) {
            if (out.size() >= AlmanacLayout.LINK_SLOTS) {
                break;
            }
            out.add(new LinkButton(AlmanacText.authored(link.textKey(), link.textKey()), link.destination()));
        }
        return List.copyOf(out);
    }

    // ---- the hero ----

    /** The season's hero: its art; else its composition; else its own picture on the plate. */
    @Nonnull
    static HeroPlan hero(@Nonnull SeasonPage page) {
        Season season = page.season();
        Timing timing = page.timing();
        Pill chip = Pill.of(AlmanacLines.chip(timing), tone(timing));
        Message title = AlmanacText.authored(season.titleKey(), season.eventId());
        Message dates = AlmanacLines.window(timing);
        String accent = ZigTokens.clampAccent(page.accentHex());
        Hero hero = page.hero();
        if (hero.art() != null) {
            return new HeroPlan(HeroKind.ART, hero.art(), null, null, null, List.of(), null, chip, title, dates, accent);
        }
        HeroComposition composition = hero.composition();
        if (composition != null) {
            String fill = composition.gradient() != null ? composition.gradient().bottomHex()
                    : composition.backgroundHex();
            String sky = composition.gradient() == null ? null : composition.gradient().topHex();
            List<HeroPicture> pictures = new ArrayList<>();
            for (HeroItem item : composition.items()) {
                pictures.add(new HeroPicture(item.itemId(), item.iconPath(),
                        new HeroBox(item.x(), item.y(), item.size(), item.size())));
            }
            return new HeroPlan(HeroKind.COMPOSED, composition.backgroundTexture(), hex(fill), hex(sky),
                    light(composition.glow()), List.copyOf(pictures), null, chip, title, dates, accent);
        }
        return new HeroPlan(HeroKind.PICTURE, null, null, null, null, List.of(), hero.iconPath(), chip, title, dates,
                accent);
    }

    @Nullable
    private static HeroLight light(@Nullable HeroGlow glow) {
        if (glow == null || hex(glow.colorHex()) == null) {
            return null;
        }
        HeroBox box = fitGlow(glow);
        return box == null ? null : new HeroLight(glow.colorHex(), box);
    }

    /**
     * A glow fitted onto the plate, since the client does not clip a child to its parent (a glow hanging past the
     * plate would spill over the page): the largest box about its authored centre that the plate holds, keeping
     * the authored size on each axis where it fits, so the radial glow may stretch into an ellipse. A glow already
     * on the plate keeps its box; one whose centre is off the plate, or that fits narrower or shorter than
     * {@link AlmanacLayout#GLOW_MIN_FITTED}, draws nothing. Hallow's Eve's (514, -78, 400) becomes 400 x 236 at
     * (514, 4).
     */
    @Nullable
    static HeroBox fitGlow(@Nonnull HeroGlow glow) {
        double half = glow.size() / 2.0;
        double cx = glow.x() + half;
        double cy = glow.y() + half;
        double halfWidth = Math.min(half, Math.min(cx, AlmanacLayout.HERO_WIDTH - cx));
        double halfHeight = Math.min(half, Math.min(cy, AlmanacLayout.HERO_HEIGHT - cy));
        int width = (int) Math.floor(halfWidth * 2.0);
        int height = (int) Math.floor(halfHeight * 2.0);
        if (width < AlmanacLayout.GLOW_MIN_FITTED || height < AlmanacLayout.GLOW_MIN_FITTED) {
            return null;
        }
        return new HeroBox((int) Math.round(cx - halfWidth), (int) Math.round(cy - halfHeight), width, height);
    }

    /** A {@code #rrggbb} data colour as the page pushes it, or null for anything else. */
    @Nullable
    private static String hex(@Nullable String colour) {
        return UiRetint.isSixDigitHex(colour) ? colour : null;
    }
}
