package com.ziggfreed.common.almanac.page;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacComponent;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.ServerTallies;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.AchievementShelf;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.BannerCard;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.GlanceMonth;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroBox;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroLight;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroPicture;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.KeepsakeShelf;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.LinkButton;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.RecordCard;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.SeasonBody;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.YearChoice;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.almanac.view.AlmanacView.Banner;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonPage;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.PillPainter;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.ui.kit.TilePainter;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Almanac: every season this server runs on the left (a ledger list, On now first, the year at a glance and
 * the record card under it), the one being read on the right (its hero, the years to read, its tiles, keepsakes,
 * achievements and links). It paints {@link AlmanacPagePlan} through the kit and decides nothing: what a season
 * says is {@link AlmanacView}'s, how the page maps it onto the kit is the plan's. Every pick (a season, a year, a
 * month) reopens the page, so every build is a full one and no row is addressed by a recomputed index. It sits in
 * the shared menu frame with the Almanac tab selected on the rail. Every {@code handleDataEvent} exit opens a
 * page, closes this one, or answers with an update, the missing-player exit included.
 */
public final class AlmanacPage extends InteractiveCustomUIPage<AlmanacEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigAlmanacPage.ui";

    /** One season's mark on a month row of the year at a glance: an 8 x 8 dot, tinted to the season's accent. */
    static final String MARK_TEMPLATE = "Pages/ZigAlmanacMonthMark.ui";

    /** The pill each earned feat shows as ({@code Pages/ZigPill.ui}, addressed {@code host[i] #Pill}). */
    static final String FEAT_TEMPLATE = DetailPainter.PILL_TEMPLATE;

    /**
     * One composed hero's item picture, appended onto the plate's {@code #HeroItems} and placed by a whole Anchor:
     * the kit's plain picture slot, an {@code AssetImage #IcoTex} painted through {@code IconRenderer.applyPlainIcon}
     * (no tooltip, no rarity square), filling the box the Anchor gives it.
     */
    static final String HERO_PICTURE = "Group { AssetImage #IcoTex { Anchor: (Full: 0); Visible: false; "
            + "FallbackTexturePath: \"UI/Custom/Common/Glyphs/Blank.png\"; } }";

    // The hero plate's own layers (Common/ZigKit.ui's @ZigHeroPlate, instanced as #Hero): the kit has no painter
    // for the plate, so the page addresses them here, and AlmanacPageDocumentTest holds each id to the kit.
    private static final String HERO = "#Hero";
    private static final String HERO_ART = HERO + " #HeroArt";
    private static final String HERO_SKY = HERO + " #HeroGradient";
    private static final String HERO_GLOW = HERO + " #HeroGlow";
    private static final String HERO_ITEMS = HERO + " #HeroItems";
    private static final String HERO_PIC = HERO + " #HeroPic";
    private static final String HERO_CHIP = HERO + " #HeroChip";
    private static final String HERO_TITLE = HERO + " #HeroTitle";
    private static final String HERO_DATES = HERO + " #HeroDates";
    private static final String HERO_NEXT = HERO + " #HeroNext";
    private static final String HERO_ACCENT = HERO + " #HeroAccent";

    // A standalone @ZigSectionHeader's label and meta (no kit painter for one outside a detail block).
    private static final String HEAD_LABEL = " #HeadLabel";
    private static final String HEAD_META = " #Meta";

    /** Where the season's row clicks go: each reopens the page on that season. */
    private static final LedgerBindings SEASON_ROWS = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return EventData.of("Action", "select").append("Season", r.id());
        }

        @Override
        public EventData section(LedgerSection s) {
            return null;
        }

        @Override
        public EventData showMore(LedgerSection s) {
            return null;
        }
    };

    @Nullable
    private String selected;

    /** The scope a year chip asked for; null reads the season's default (the year on now, else the last taken part in). */
    @Nullable
    private Scope requested;

    /** The plan the last build painted: a month click and a link click are answered from it, never recomputed. */
    @Nullable
    private AlmanacPagePlan painted;

    /** The rail this build painted. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    public AlmanacPage(@Nonnull PlayerRef playerRef, @Nullable String selectedEventId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, AlmanacEventData.CODEC);
        this.selected = selectedEventId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        // The shared menu's rail, the Almanac tab selected; painted before anything that could fail.
        rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                MenuSlot.ALMANAC.id(), false);
        AlmanacPagePlan plan;
        try {
            plan = plan(store, ref);
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the page could not read its seasons, so it shows none: " + t.getMessage());
            plan = AlmanacPagePlan.of(List.of(), Map.of(), null, new AlmanacView.Record(0L, 0L), List.of(), Map.of(),
                    0, null, null);
        }
        painted = plan;
        selected = plan.selected();
        try {
            paint(cmd, events, plan, playerRef);
        } catch (Throwable t) {
            // A build never throws: the page keeps what was painted before the fault, and the rail still answers.
            SafeLog.warn("[almanac] the page could not paint every part of the season: " + t.getMessage());
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull AlmanacEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            answer();
            return;
        }
        if (rail.handle(data.menu, store, ref, player, this::answer)) {
            return;
        }
        String action = data.action == null ? "" : data.action;
        switch (action) {
            case "select" -> {
                if (data.season == null || data.season.isBlank()) {
                    answer();
                    return;
                }
                selected = data.season;
                requested = scope(data.year);
                reopen(ref, store, player);
            }
            case "month" -> {
                String first = monthFirst(data.month);
                if (first == null) {
                    answer();
                    return;
                }
                selected = first;
                requested = null;
                reopen(ref, store, player);
            }
            case "link" -> {
                LinkButton link = link(data.link);
                if (link == null || !openLink(link, ref, store, player)) {
                    answer();
                }
            }
            default -> player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    /** An empty update, for an event nothing opened for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        try {
            player.getPageManager().openCustomPage(ref, store, this);
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the page could not reopen: " + t.getMessage());
            answer();
        }
    }

    private boolean openLink(@Nonnull LinkButton link, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        try {
            return Destinations.open(link.destination(), DestinationContext.of(store, ref, player));
        } catch (Throwable t) {
            SafeLog.warn("[almanac] a season's link could not open: " + t.getMessage());
            return false;
        }
    }

    /** A year chip's pick as a scope: {@link AlmanacEventData#EVERY}, a year, or null (the season's default). */
    @Nullable
    static Scope scope(@Nullable String year) {
        if (year == null || year.isBlank()) {
            return null;
        }
        if (AlmanacEventData.EVERY.equals(year.trim())) {
            return Scope.EVERY;
        }
        try {
            return new Scope(Integer.parseInt(year.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first season the last build marked in {@code month}, or null. */
    @Nullable
    private String monthFirst(@Nullable String month) {
        AlmanacPagePlan plan = painted;
        if (plan == null || month == null) {
            return null;
        }
        try {
            int m = Integer.parseInt(month.trim());
            for (GlanceMonth row : plan.months()) {
                if (row.month() == m) {
                    return row.firstEventId();
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** The link the last build painted at {@code index}, or null. */
    @Nullable
    private LinkButton link(@Nullable String index) {
        AlmanacPagePlan plan = painted;
        SeasonBody body = plan == null ? null : plan.body();
        if (body == null || index == null) {
            return null;
        }
        try {
            int i = Integer.parseInt(index.trim());
            return i >= 0 && i < body.links().size() ? body.links().get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== reading ====================

    /** Everything the page shows for this player now, read once per build. */
    @Nonnull
    private AlmanacPagePlan plan(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        long now = System.currentTimeMillis();
        Map<String, AlmanacEntryAsset> pages = AlmanacEntryConfig.getInstance().all();
        AlmanacCalendar calendar = OccurrenceAlmanacCalendar.INSTANCE;
        List<Season> seasons = AlmanacView.seasons(pages, calendar);
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = achievementSubject(store, ref);
        CounterMap tallies = AlmanacComponent.talliesOf(store, ref);

        Map<String, Timing> timings = new HashMap<>();
        Map<String, String> accents = new HashMap<>();
        for (Season season : seasons) {
            timings.put(season.eventId(), AlmanacView.timing(season, calendar, now));
            AlmanacEntryAsset page = pages.get(season.eventId());
            if (page != null && page.accent() != null) {
                accents.put(season.eventId(), page.accent());
            }
        }
        Season season = AlmanacView.pick(seasons, selected);
        SeasonPage page = season == null ? null : AlmanacView.page(season, pages.get(season.eventId()), tallies,
                engine, subject, requested, calendar, ServerTallies.shared(), now);
        Banner banner = subject == null ? null : AlmanacView.banner(engine, subject);
        Achievement bannerAchievement = banner == null ? null : engine.achievement(banner.achievementId());
        int month = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).getMonthValue();
        return AlmanacPagePlan.of(seasons, timings, page, AlmanacView.record(seasons, pages, tallies, engine, subject),
                AlmanacView.yearAtAGlance(seasons, calendar, now), accents, month, banner,
                bannerAchievement == null ? null : bannerAchievement.icon());
    }

    @Nullable
    private static Subject achievementSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return ProgressionRuntime.subjects().achievementSubject(store, ref);
        } catch (Throwable t) {
            SafeLog.warn("[almanac] no achievement subject for the page: " + t.getMessage());
            return null;
        }
    }

    // ==================== painting ====================

    /**
     * Paint {@code plan} into a freshly appended page document. Package-private so the document test can drive it
     * and hold every selector it sends to the documents.
     */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull AlmanacPagePlan plan,
            @Nullable PlayerRef viewer) {
        cmd.set("#AlmanacTitle.TextSpans", AlmanacText.line("title"));
        cmd.set("#AlmanacLead.TextSpans", AlmanacText.line("lead"));
        paintBanner(cmd, plan.banner());
        SeasonBody body = plan.body();
        if (body == null) {
            cmd.set("#SeasonList.Visible", false);
            cmd.set("#Glance.Visible", false);
            cmd.set("#RecordCard.Visible", false);
            cmd.set("#RightColumn.Visible", false);
            cmd.set("#AlmanacEmpty.Visible", true);
            EmptyStatePainter.paint(cmd, events, "#AlmanacEmpty", plan.empty(), null);
            return;
        }
        LedgerPainter.paint(cmd, events, "#SeasonList", plan.seasons(), Set.of(), plan.selected(), SEASON_ROWS,
                RowSize.STANDARD, viewer);
        paintGlance(cmd, events, plan.months());
        paintRecord(cmd, plan.record());
        paintHero(cmd, body.hero());
        paintBody(cmd, events, body, viewer);
    }

    private static void paintBanner(@Nonnull UICommandBuilder cmd, @Nullable BannerCard banner) {
        cmd.set("#BannerCard.Visible", banner != null);
        if (banner == null) {
            return;
        }
        IconRenderer.applyPlainIcon(cmd, "#BannerPic", banner.picture().itemId(), banner.picture().texturePath());
        cmd.set("#BannerName.TextSpans", banner.name());
        optional(cmd, "#BannerMeta", banner.meta());
        Float fraction = banner.fraction();
        cmd.set("#BannerBar.Visible", fraction != null);
        if (fraction != null) {
            cmd.set("#BannerBar #Bar.Value", fraction.floatValue());
        }
    }

    private static void paintGlance(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<GlanceMonth> months) {
        cmd.set("#GlanceTitle.TextSpans", AlmanacText.line("glance.title"));
        for (GlanceMonth month : months) {
            String row = "#Month" + month.month();
            ZigRichButton.text(cmd, row, month.label());
            if (month.current()) {
                ZigRichButton.color(cmd, row, ZigTokens.INK_STRONG);
            }
            String marks = row + " #Marks";
            List<String> hexes = month.markHexes();
            for (int i = 0; i < hexes.size(); i++) {
                cmd.append(marks, MARK_TEMPLATE);
                UiRetint.retintColor(cmd, marks + "[" + i + "]", hexes.get(i));
            }
            if (month.firstEventId() != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, row,
                        EventData.of("Action", "month").append("Month", String.valueOf(month.month())), false);
            }
        }
    }

    private static void paintRecord(@Nonnull UICommandBuilder cmd, @Nonnull RecordCard record) {
        cmd.set("#RecordTitle.TextSpans", AlmanacText.line("record.title"));
        cmd.set("#RecordSeasonsValue.TextSpans", record.seasonsFigure());
        cmd.set("#RecordSeasonsLabel.TextSpans", record.seasonsCaption());
        cmd.set("#RecordKeepsakesValue.TextSpans", record.keepsakesFigure());
        cmd.set("#RecordKeepsakesLabel.TextSpans", record.keepsakesCaption());
    }

    /**
     * The hero's layers, each set both ways so nothing the template ships shows by accident: the art layer only for
     * art (or a composition's texture); the flat fill over the plate, the tinted sky, the glow fitted onto the plate
     * and the item pictures for a composition; the season's own picture otherwise; then the chip, the name, the
     * dates, the line under them (a monthly or weekly season's next run) and the accent strip.
     */
    private static void paintHero(@Nonnull UICommandBuilder cmd, @Nonnull HeroPlan hero) {
        String art = hero.artTexture();
        cmd.set(HERO_ART + ".Visible", art != null);
        if (art != null) {
            cmd.set(HERO_ART + ".AssetPath", art);
        }
        if (hero.fillHex() != null) {
            UiRetint.fill(cmd, HERO, hero.fillHex());
        }
        String sky = hero.skyHex();
        cmd.set(HERO_SKY + ".Visible", sky != null);
        if (sky != null) {
            UiRetint.retintColor(cmd, HERO_SKY, sky);
        }
        HeroLight glow = hero.glow();
        cmd.set(HERO_GLOW + ".Visible", glow != null);
        if (glow != null) {
            cmd.setObject(HERO_GLOW + ".Anchor", anchor(glow.box()));
            UiRetint.retintColor(cmd, HERO_GLOW, glow.colorHex());
        }
        List<HeroPicture> pictures = hero.pictures();
        for (int i = 0; i < pictures.size(); i++) {
            HeroPicture picture = pictures.get(i);
            String slot = HERO_ITEMS + "[" + i + "]";
            cmd.appendInline(HERO_ITEMS, HERO_PICTURE);
            cmd.setObject(slot + ".Anchor", anchor(picture.box()));
            IconRenderer.applyPlainIcon(cmd, slot, null, picture.iconPath());
        }
        String own = hero.pictureTexture();
        cmd.set(HERO_PIC + ".Visible", own != null);
        if (own != null) {
            IconRenderer.applyPlainIcon(cmd, HERO_PIC, null, own);
        }
        PillPainter.paint(cmd, HERO_CHIP, hero.chip());
        cmd.set(HERO_TITLE + ".TextSpans", hero.title());
        optional(cmd, HERO_DATES, hero.dates());
        optional(cmd, HERO_NEXT, hero.next());
        UiRetint.fill(cmd, HERO_ACCENT, hero.accentHex());
    }

    private static void paintBody(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull SeasonBody body, @Nullable PlayerRef viewer) {
        optional(cmd, "#Flavor", body.flavor());

        List<YearChoice> years = body.years();
        cmd.set("#YearChips.Visible", !years.isEmpty());
        for (YearChoice year : years) {
            SegmentPainter.append(cmd, events, "#YearChips", year.label(), year.on(), year.check(), year.dot(),
                    EventData.of("Action", "select").append("Season", body.eventId()).append("Year", year.value()));
        }

        header(cmd, "#ScopeHeader", body.scopeHeader(), body.scopeMeta());
        cmd.set("#StatGrid.Visible", !body.tiles().isEmpty());
        TilePainter.stats(cmd, "#StatGrid", body.tiles());
        cmd.set("#Hint.Visible", body.hint());
        if (body.hint()) {
            cmd.set("#Hint.TextSpans", AlmanacText.line("hint.first_time"));
        }

        KeepsakeShelf keepsakes = body.keepsakes();
        cmd.set("#KeepsakeHeader.Visible", keepsakes != null);
        cmd.set("#KeepsakeShelf.Visible", keepsakes != null);
        if (keepsakes != null) {
            header(cmd, "#KeepsakeHeader", AlmanacText.line("keepsakes.title"), keepsakes.meta());
            TilePainter.keepsakes(cmd, "#KeepsakeShelf", keepsakes.tiles());
        }

        AchievementShelf achievements = body.achievements();
        cmd.set("#AchHeader.Visible", achievements != null);
        cmd.set("#AchBar.Visible", achievements != null && achievements.fraction() != null);
        cmd.set("#FeatList.Visible", achievements != null && !achievements.feats().isEmpty());
        if (achievements != null) {
            header(cmd, "#AchHeader", AlmanacText.line("achievements.title"), achievements.meta());
            if (achievements.fraction() != null) {
                cmd.set("#AchBar #Bar.Value", achievements.fraction().floatValue());
            }
            for (int i = 0; i < achievements.feats().size(); i++) {
                cmd.append("#FeatList", FEAT_TEMPLATE);
                PillPainter.paint(cmd, "#FeatList[" + i + "] #Pill", achievements.feats().get(i));
            }
        }

        List<LinkButton> links = body.links();
        cmd.set("#Links.Visible", !links.isEmpty());
        for (int i = 0; i < AlmanacLayout.LINK_SLOTS; i++) {
            String button = "#Link" + (i + 1);
            boolean shown = i < links.size();
            cmd.set(button + ".Visible", shown);
            if (shown) {
                ZigRichButton.text(cmd, button, links.get(i).label());
                events.addEventBinding(CustomUIEventBindingType.Activating, button,
                        EventData.of("Action", "link").append("Link", String.valueOf(i)), false);
            }
        }
    }

    /** A standalone section header's label and meta (the meta hidden when there is none). */
    private static void header(@Nonnull UICommandBuilder cmd, @Nonnull String header, @Nonnull Message label,
            @Nullable Message meta) {
        cmd.set(header + HEAD_LABEL + ".TextSpans", label);
        optional(cmd, header + HEAD_META, meta);
    }

    /** A label shown with {@code text}, or hidden when there is none. */
    private static void optional(@Nonnull UICommandBuilder cmd, @Nonnull String label, @Nullable Message text) {
        if (text != null) {
            cmd.set(label + ".TextSpans", text);
        }
        cmd.set(label + ".Visible", text != null);
    }

    /** A whole Anchor for a box on the plate: the only form a layout input changes in from Java. */
    @Nonnull
    private static Anchor anchor(@Nonnull HeroBox box) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(box.x()));
        anchor.setTop(Value.of(box.y()));
        anchor.setWidth(Value.of(box.width()));
        anchor.setHeight(Value.of(box.height()));
        return anchor;
    }
}
