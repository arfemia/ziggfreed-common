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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacCollection;
import com.ziggfreed.common.almanac.AlmanacComponent;
import com.ziggfreed.common.almanac.AlmanacStatsImport;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.ServerTallies;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.AchievementShelf;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.AchievementsPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.BannerCard;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.BannerPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.CollectionPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.GlanceMonth;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroBox;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.KeepsakeShelf;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.KeepsakesPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.LinkButton;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.LinksPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.RecordCard;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.SeasonBody;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.TalliesPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.YearChoice;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.almanac.view.AlmanacView.Banner;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonPage;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.icon.Portraits;
import com.ziggfreed.common.inventory.InventoryUtil;
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
import com.ziggfreed.common.ui.kit.PlatePainter;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.ui.kit.TilePainter;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.page.PagePainter;
import com.ziggfreed.common.ui.page.PartCanvas;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Almanac: every season this server runs on the left (a ledger list, On now first, the year at a glance and
 * the record card under it), the one being read on the right (its hero, then its parts in the order its page writes
 * them: inline banners, the items grid, the years to read and its tiles, keepsakes, achievements with the button
 * into the book, and links; each part one of the page kit's registered parts ({@link AlmanacParts}), its template
 * appended into {@link #SECTIONS} and painted by its painter here). When it reads a season it first
 * looks at the player's bag for the items that season's grids list. It paints {@link AlmanacPagePlan} through the
 * kit and decides nothing: what a season says is {@link AlmanacView}'s, how the page maps it onto the kit is the
 * plan's. Every pick (a season, a year, a month) reopens the page, so every build is a full one and no row is
 * addressed by a recomputed index. It sits in
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
     * The season body's one host for its parts: the page appends one template per part, in the order the season's
     * page writes them, and addresses each {@code #Sections[i] #Id}.
     */
    static final String SECTIONS = "#Sections";

    /** The year chips, the scope header, the stat tiles and the first-time hint. */
    static final String TALLIES_TEMPLATE = "Pages/ZigAlmanacTallies.ui";

    /** The keepsake header and shelf. */
    static final String KEEPSAKES_TEMPLATE = "Pages/ZigAlmanacKeepsakes.ui";

    /** The achievements header, bar and feats, and the button into the book. */
    static final String ACHIEVEMENTS_TEMPLATE = "Pages/ZigAlmanacAchievements.ui";

    /** The season's link buttons. */
    static final String LINKS_TEMPLATE = "Pages/ZigAlmanacLinks.ui";

    /** An inline banner: a plate of its own height with its art or composition, its words and its button. */
    static final String BANNER_TEMPLATE = "Pages/ZigAlmanacBanner.ui";

    /** The items grid: its header and bar, its line, the kit's item slots, and its button. */
    static final String COLLECTION_TEMPLATE = "Pages/ZigAlmanacCollection.ui";

    /** Every part's template. */
    static final List<String> SECTION_TEMPLATES = List.of(TALLIES_TEMPLATE, KEEPSAKES_TEMPLATE,
            ACHIEVEMENTS_TEMPLATE, LINKS_TEMPLATE, BANNER_TEMPLATE, COLLECTION_TEMPLATE);

    /**
     * One composed hero's or banner's item picture: the kit's plain picture slot ({@link PlatePainter#PICTURE}),
     * appended onto a plate's pictures host and placed by a whole Anchor.
     */
    static final String HERO_PICTURE = PlatePainter.PICTURE;

    /**
     * One composed picture that is an NPC's portrait: the same plain slot, falling back to the shared missing-picture
     * icon ({@link Portraits#MISSING_TEXTURE}) for a model no still was rendered for ({@link PlatePainter#PORTRAIT}).
     */
    static final String HERO_PORTRAIT = PlatePainter.PORTRAIT;

    /**
     * The hero plate (Common/ZigKit.ui's @ZigHeroPlate) as the page document instances it: the kit's
     * {@link PlatePainter} spells its layers' ids, and AlmanacPageDocumentTest holds each one to the kit.
     */
    private static final String HERO = "#Hero";

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

    /**
     * The plan the last build painted: a month click, a link click and a part's button are answered from it, never
     * recomputed.
     */
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
            case "section" -> {
                AlmanacPagePlan plan = painted;
                SeasonBody body = plan == null ? null : plan.body();
                LinkButton button = body == null ? null : body.sectionButton(data.section);
                if (button == null || !openLink(button, ref, store, player)) {
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
        AlmanacEntryAsset reading = season == null ? null : pages.get(season.eventId());
        // The owned marks are written into the Almanac record itself, so the look comes before the read.
        markHeld(store, ref, AlmanacComponent.talliesOf(store, ref), reading);
        CounterMap tallies = AlmanacStatsImport.read(store, ref);
        SeasonPage page = season == null ? null : AlmanacView.page(season, reading, tallies, engine, subject,
                requested, calendar, ServerTallies.shared(), now);
        Banner banner = subject == null ? null : AlmanacView.banner(engine, subject);
        Achievement bannerAchievement = banner == null ? null : engine.achievement(banner.achievementId());
        int month = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).getMonthValue();
        return AlmanacPagePlan.of(seasons, timings, page, AlmanacView.record(seasons, pages, tallies, engine, subject),
                AlmanacView.yearAtAGlance(seasons, calendar, now), accents, month, banner,
                bannerAchievement == null ? null : bannerAchievement.icon());
    }

    /**
     * Mark owned every item the season's collections list that the player holds right now, so a bag filled before the
     * owned marks existed reveals on the first look, with no inventory change needed. Never throws.
     */
    private static void markHeld(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CounterMap tallies, @Nullable AlmanacEntryAsset page) {
        try {
            AlmanacCollection.markHeld(tallies, AlmanacCollection.itemsOf(page),
                    id -> InventoryUtil.has(store, ref, id, 1));
        } catch (Throwable t) {
            SafeLog.fine("[almanac] the bag could not be looked at for the owned marks: " + t.getMessage());
        }
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
        // The tall row: a season's meta ("On now - 27 days left") runs onto a second line in the narrow column.
        LedgerPainter.paint(cmd, events, "#SeasonList", plan.seasons(), Set.of(), plan.selected(), SEASON_ROWS,
                RowSize.TALL, viewer);
        paintGlance(cmd, events, plan.months());
        paintRecord(cmd, plan.record());
        // The hero's layers, then its picture, chip, name, dates lines and accent strip: the kit's plate painter.
        PlatePainter.paintHero(cmd, HERO, body.hero());
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
     * The flavor, then each of the season's parts in the plan's order, through the page kit: its part's template
     * appended into {@link #SECTIONS} and painted under {@code #Sections[i]} by the part's painter (below).
     */
    private static void paintBody(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull SeasonBody body, @Nullable PlayerRef viewer) {
        optional(cmd, "#Flavor", body.flavor());
        PagePainter.paint(cmd, events, SECTIONS, body.sections(), viewer);
    }

    // ==================== the parts' painters (AlmanacParts) ====================

    /** The year chips (a click reopens the page on that year), the scope header, the tiles and the first-time hint. */
    static void paintTallies(@Nonnull PartCanvas canvas, @Nonnull TalliesPlan tallies) {
        UICommandBuilder cmd = canvas.cmd();
        String chips = canvas.at("#YearChips");
        List<YearChoice> years = tallies.years();
        cmd.set(chips + ".Visible", !years.isEmpty());
        for (YearChoice year : years) {
            SegmentPainter.append(cmd, canvas.events(), chips, year.label(), year.on(), year.check(), year.dot(),
                    EventData.of("Action", "select").append("Season", tallies.eventId()).append("Year", year.value()));
        }
        header(cmd, canvas.at("#ScopeHeader"), tallies.scopeHeader(), tallies.scopeMeta());
        String grid = canvas.at("#StatGrid");
        cmd.set(grid + ".Visible", !tallies.tiles().isEmpty());
        TilePainter.stats(cmd, grid, tallies.tiles());
        String hint = canvas.at("#Hint");
        cmd.set(hint + ".Visible", tallies.hint());
        if (tallies.hint()) {
            cmd.set(hint + ".TextSpans", AlmanacText.line("hint.first_time"));
        }
    }

    /** The keepsake header and shelf (the plan places this part only with a shelf). */
    static void paintKeepsakes(@Nonnull PartCanvas canvas, @Nonnull KeepsakesPlan part) {
        UICommandBuilder cmd = canvas.cmd();
        KeepsakeShelf keepsakes = part.keepsakes();
        String header = canvas.at("#KeepsakeHeader");
        String shelf = canvas.at("#KeepsakeShelf");
        cmd.set(header + ".Visible", keepsakes != null);
        cmd.set(shelf + ".Visible", keepsakes != null);
        if (keepsakes != null) {
            header(cmd, header, AlmanacText.line("keepsakes.title"), keepsakes.meta());
            TilePainter.keepsakes(cmd, shelf, keepsakes.tiles());
        }
    }

    /** The achievements header, bar and feats, then the button into the book when the plan resolved one. */
    static void paintAchievements(@Nonnull PartCanvas canvas, @Nonnull AchievementsPlan part) {
        UICommandBuilder cmd = canvas.cmd();
        AchievementShelf achievements = part.achievements();
        String header = canvas.at("#AchHeader");
        String bar = canvas.at("#AchBar");
        String feats = canvas.at("#FeatList");
        cmd.set(header + ".Visible", achievements != null);
        cmd.set(bar + ".Visible", achievements != null && achievements.fraction() != null);
        cmd.set(feats + ".Visible", achievements != null && !achievements.feats().isEmpty());
        if (achievements != null) {
            header(cmd, header, AlmanacText.line("achievements.title"), achievements.meta());
            if (achievements.fraction() != null) {
                cmd.set(bar + " #Bar.Value", achievements.fraction().floatValue());
            }
            for (int j = 0; j < achievements.feats().size(); j++) {
                cmd.append(feats, FEAT_TEMPLATE);
                PillPainter.paint(cmd, feats + "[" + j + "] #Pill", achievements.feats().get(j));
            }
        }
        sectionButton(canvas, "#AchCta", part.cta());
    }

    /** The season's link buttons, filled in order; each click opens its link by the index it carries. */
    static void paintLinks(@Nonnull PartCanvas canvas, @Nonnull LinksPlan part) {
        UICommandBuilder cmd = canvas.cmd();
        List<LinkButton> links = part.links();
        cmd.set(canvas.at("#Links.Visible"), !links.isEmpty());
        for (int i = 0; i < AlmanacLayout.LINK_SLOTS; i++) {
            String button = canvas.at("#Link" + (i + 1));
            boolean shown = i < links.size();
            cmd.set(button + ".Visible", shown);
            if (shown) {
                ZigRichButton.text(cmd, button, links.get(i).label());
                canvas.events().addEventBinding(CustomUIEventBindingType.Activating, button,
                        EventData.of("Action", "link").append("Link", String.valueOf(i)), false);
            }
        }
    }

    /**
     * An inline banner (not {@code paintBanner}, the left column's card): its plate's whole Anchor for its height,
     * then its layers through the kit's {@link PlatePainter} (set both ways, as the hero's are); the fade under its
     * words; its title and line; its button.
     */
    static void paintPlate(@Nonnull PartCanvas canvas, @Nonnull BannerPlan banner) {
        UICommandBuilder cmd = canvas.cmd();
        String plate = canvas.at("#Plate");
        cmd.setObject(plate + ".Anchor",
                PlatePainter.anchor(new HeroBox(0, 0, AlmanacLayout.PLATE_WIDTH, banner.height())));
        PlatePainter.paintLayers(cmd, new PlatePainter.Ids(plate, canvas.at("#PlateArt"), canvas.at("#PlateSky"),
                canvas.at("#PlateGlow"), plate + " #PlateItems"), banner);
        cmd.set(canvas.at("#PlateFade.Visible"), banner.title() != null || banner.line() != null);
        optional(cmd, canvas.at("#PlateTitle"), banner.title());
        optional(cmd, canvas.at("#PlateLine"), banner.line());
        sectionButton(canvas, "#PlateButton", banner.button());
    }

    /** The items grid: its header and count, its bar, its line, a kit item slot per item, and its button. */
    static void paintCollection(@Nonnull PartCanvas canvas, @Nonnull CollectionPlan collection) {
        UICommandBuilder cmd = canvas.cmd();
        header(cmd, canvas.at("#ColHeader"), collection.title(), collection.meta());
        cmd.set(canvas.at("#ColBar #Bar.Value"), collection.fraction());
        optional(cmd, canvas.at("#ColLine"), collection.line());
        TilePainter.itemSlots(cmd, canvas.at("#ColGrid"), collection.slots());
        sectionButton(canvas, "#ColButton", collection.button());
    }

    /**
     * A part's button ({@code id} inside its template): shown and labelled with a link, bound to open it by the part's
     * index, which the click is answered from; hidden without one.
     */
    private static void sectionButton(@Nonnull PartCanvas canvas, @Nonnull String id, @Nullable LinkButton link) {
        String button = canvas.at(id);
        canvas.cmd().set(button + ".Visible", link != null);
        if (link != null) {
            ZigRichButton.text(canvas.cmd(), button, link.label());
            canvas.events().addEventBinding(CustomUIEventBindingType.Activating, button,
                    EventData.of("Action", "section").append("Section", String.valueOf(canvas.index())), false);
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
}
