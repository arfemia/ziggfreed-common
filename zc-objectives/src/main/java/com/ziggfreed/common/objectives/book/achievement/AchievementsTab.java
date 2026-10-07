package com.ziggfreed.common.objectives.book.achievement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.BookActions;
import com.ziggfreed.common.objectives.book.BookContext;
import com.ziggfreed.common.objectives.book.BookState;
import com.ziggfreed.common.objectives.book.BookTab;
import com.ziggfreed.common.objectives.book.LedgerLayout;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps.MilestoneView;
import com.ziggfreed.common.objectives.book.ObjectiveBookEventData;
import com.ziggfreed.common.objectives.book.ObjectiveBookMenu;
import com.ziggfreed.common.objectives.book.ObjectiveBookPage;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.CollectionTile;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.KitText;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerIndex;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.LedgerSource;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.TilePainter;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.util.SafeLog;

/**
 * The book's Achievements tab: three views on one document ({@link #DOCUMENT}), chosen by the segment at the left of
 * the toolbar and carried in {@code BookState.view()}.
 * <ul>
 *   <li><b>Overview</b> (the default): the hero (earned of total, points, one bar, the rewards still to collect with
 *       Show), the next points milestone (the consumer's ladder; hidden without one), the category tiles and three
 *       strips (Pinned, Recently earned, Nearly there). A tile opens Browse on its category, the Feats tile on the
 *       Feats status, a strip row opens Browse on that row.</li>
 *   <li><b>Browse</b>: the status segments, the category and sort dropdowns, the list of sections
 *       ({@link AchievementReader#browse}) and the selected achievement's page ({@link AchievementReader#page}).</li>
 *   <li><b>Statistics</b>, while any module contributed a section to {@code LedgerContributions.STATISTICS}
 *       ({@link StatisticsView}): the same list and page, from the sources.</li>
 * </ul>
 *
 * <p><b>What it keeps from the book before it.</b> The shelf rule and the ladder collapse (the reader's); Pin only where
 * {@link AchievementPinOffer} offers it, through the page's toggle, with the cap's toast ({@code BookVerbs}); the page's
 * Collect bound once and acting on the row the page shows; milestones collected through the consumer's claim seam;
 * the header's earned count with feats out. A selection, a section toggle, a pin and a claim that moves no row answer
 * with a partial update (the scroll stays); a claim that moves a row, a filter, a view and a search reopen the book on
 * the same state, so the selection rides every reopen and Overview stays one click away from any selection.
 *
 * <p><b>Rows past the cap.</b> A section shows {@link LedgerSection#DEFAULT_CAP} rows and a "Show N more" row; the
 * extra rows a player asked for are this page's own memory (the shell keeps one tab per page), and a section always
 * reaches far enough to show the selected row.
 *
 * <p>The tab never spells a template's child ids: the kit's painters address the segments, rows, tiles, the page and
 * the empty states inside the ids this class names.
 */
public final class AchievementsTab implements BookTab {

    /** The tab's document, appended into the shell's {@code #TabBody}. */
    public static final String DOCUMENT = "Pages/ZigBookAchievements.ui";

    // ==================== the document's ids (ZigBookAchievementsDocumentTest holds them) ====================

    static final String ROOT = "#Achievements";
    static final String TOOLBAR = "#Toolbar";
    static final String VIEWS = "#Views";
    static final String SEARCH = "#Search";
    static final String FILTERS = "#Filters";
    static final String STATUSES = "#Statuses";
    static final String CATEGORY = "#Category";
    static final String SORT = "#Sort";

    static final String OVERVIEW = "#Overview";
    static final String HERO = "#Hero";
    static final String HERO_EARNED = "#HeroEarned";
    static final String HERO_POINTS = "#HeroPoints";
    static final String HERO_BAR = "#HeroBar";
    static final String HERO_PERCENT = "#HeroPercent";
    static final String HERO_WAITING = "#HeroWaiting";
    static final String HERO_WAITING_LINE = "#HeroWaitingLine";
    static final String HERO_SHOW = "#HeroShow";
    static final String MILESTONE = "#Milestone";
    static final String MS_TITLE = "#MsTitle";
    static final String MS_COLLECT = "#MsCollect";
    static final String MS_BAR = "#MsBar";
    static final String MS_COUNT = "#MsCount";
    static final String MS_REWARDS = "#MsRewards";
    static final String MS_AFTER = "#MsAfter";
    /** The milestone card's track row, hidden until a ladder of two rungs or more ({@link MilestoneTrack}). */
    static final String TRACK = "#Track";
    /** The track row's rung host, where {@link MilestoneTrack} places its markers along the bar. */
    static final String RUNGS = "#Rungs";
    static final String CATEGORIES_LABEL = "#CategoriesLabel";
    static final String TILES = "#Tiles";
    static final String PINNED_LIST = "#PinnedList";
    static final String PINNED_EMPTY = "#PinnedEmpty";
    static final String RECENT_LIST = "#RecentList";
    static final String RECENT_EMPTY = "#RecentEmpty";
    static final String NEARLY_LIST = "#NearlyList";
    static final String NEARLY_EMPTY = "#NearlyEmpty";

    static final String SPLIT = "#Split";
    static final String LIST = "#List";
    static final String LIST_EMPTY = "#ListEmpty";
    static final String PAGE = "#Page";
    static final String PAGE_EMPTY = "#PageEmpty";

    /** Across the whole tab, when there is nothing at all to list. */
    static final String EMPTY = "#AchievementsEmpty";

    /** Layout-only ids the document test reads the numbers off. */
    static final String LIST_COLUMN = "#ListColumn";
    static final String SPLIT_GUTTER = "#SplitGutter";
    static final String PAGE_CARD = "#PageCard";
    static final String CARD_GAP_ID = "#CardGap";
    static final String PINNED_STRIP = "#PinnedStrip";
    static final String RECENT_STRIP = "#RecentStrip";
    static final String NEARLY_STRIP = "#NearlyStrip";
    static final String STRIP_GAP_ONE = "#StripGapOne";
    static final String STRIP_GAP_TWO = "#StripGapTwo";

    /** Every id this class addresses by name (each must exist in the document, once). */
    static final List<String> ADDRESSED = List.of(ROOT, TOOLBAR, VIEWS, SEARCH, FILTERS, STATUSES, CATEGORY, SORT,
            OVERVIEW, HERO, HERO_EARNED, HERO_POINTS, HERO_BAR, HERO_PERCENT, HERO_WAITING, HERO_WAITING_LINE,
            HERO_SHOW, MILESTONE, MS_TITLE, MS_COLLECT, MS_BAR, MS_COUNT, MS_REWARDS, MS_AFTER, TRACK, RUNGS,
            CATEGORIES_LABEL, TILES, PINNED_LIST, PINNED_EMPTY, RECENT_LIST, RECENT_EMPTY, NEARLY_LIST, NEARLY_EMPTY,
            SPLIT, LIST, LIST_EMPTY, PAGE, PAGE_EMPTY, EMPTY);

    // ==================== the tab's own numbers (LedgerLayout holds the book's) ====================

    /** One appended segment ({@code Pages/ZigSegment.ui}): 132 wide and 6 to the next. */
    static final int SEGMENT_STEP = 138;
    static final int VIEWS_WIDTH = 3 * SEGMENT_STEP;
    static final int STATUSES_WIDTH = BrowseFilter.STATUSES.size() * SEGMENT_STEP;
    static final int SEARCH_WIDTH = 346;
    static final int CATEGORY_WIDTH = 240;
    static final int SORT_WIDTH = 220;
    static final int TOOL_GAP = 8;

    /** What the scrolling overview's content gets, inside its scroll gutter. */
    static final int OVERVIEW_WIDTH = LedgerLayout.INNER_WIDTH - LedgerLayout.SCROLL_GUTTER;
    static final int CARD_HEIGHT = 132;
    static final int HERO_WIDTH = 620;
    static final int CARD_GAP = 8;
    static final int MILESTONE_WIDTH = 630;
    static final int STRIP_WIDTH = 408;
    static final int STRIP_GAP = 17;
    /** One category tile ({@code Pages/ZigCollectionTile.ui}): 196 wide and its own 8 to the next. */
    static final int TILE_STEP = 196 + 8;
    static final int TILES_PER_ROW = 6;

    /**
     * The category grid's layout mode, written once in the document's {@code #Tiles}; the document test holds the
     * two together. Its fallback, should a client stop wrapping it, is {@code LeftCenterWrap}.
     */
    static final String TILE_LAYOUT = "LeftWrap";

    /**
     * Whether a section head opens or closes its section in place (a partial update that appends a closed section's
     * rows the first time it opens) rather than reopening the book with the toggle recorded. One switch: false
     * hands every toggle to the shell, which reopens on the same state.
     */
    static final boolean SECTION_IN_PLACE = true;

    /** The "Show N more" row's action ({@code Section} names the section). */
    static final String MORE = "more";

    /** A page button's action: {@code Id} names its slot ({@link ActionSlot}) or {@link #TOGGLE}. */
    static final String ACT = "act";

    /** {@link #ACT}'s id for the page's header toggle. */
    static final String TOGGLE = "toggle";

    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    private static final List<String> SPLIT_VIEWS = List.of(BookState.VIEW_BROWSE, BookState.VIEW_STATISTICS);

    // ==================== what this page last drew (one tab per page instance) ====================

    /** Each list painted, by its id: what it was read as, what it showed, and where its rows went. */
    private final Map<String, Painted> lists = new LinkedHashMap<>();

    /** The rows a player asked to see per section ("Show N more"), for this page's life. */
    private final Map<String, Integer> caps = new HashMap<>();

    /** The view painted, or null before a build. */
    @Nullable
    private String view;

    /** The row whose page is painted (the selection, else the list's first row), or null. */
    @Nullable
    private String shown;

    @Nonnull
    @Override
    public String id() {
        return ObjectiveBookPage.TAB_ACHIEVEMENTS;
    }

    @Nonnull
    @Override
    public String document() {
        return DOCUMENT;
    }

    @Nonnull
    @Override
    public String searchRow() {
        return SEARCH;
    }

    // ==================== the plan ====================

    /**
     * What one build shows, decided before anything paints.
     *
     * @param views      the view segments offered, in order
     * @param view       the view shown: the state's own when offered, else Overview
     * @param selectedId the state's selection, carried whatever the view
     * @param milestones whether the consumer ships a milestone ladder
     */
    record Plan(@Nonnull List<String> views, @Nonnull String view, @Nullable String selectedId, boolean milestones) {

        boolean overview() {
            return BookState.VIEW_OVERVIEW.equals(view);
        }

        boolean browse() {
            return BookState.VIEW_BROWSE.equals(view);
        }

        boolean statistics() {
            return BookState.VIEW_STATISTICS.equals(view);
        }

        /** The list and the page. */
        boolean split() {
            return !overview();
        }

        /** Browse's second toolbar row: the status segments, the category and the sort. */
        boolean filters() {
            return browse();
        }

        /** The search row; from the overview a search opens Browse. */
        boolean search() {
            return !statistics();
        }

        boolean milestoneCard() {
            return overview() && milestones;
        }

        boolean milestoneStat() {
            return milestones;
        }
    }

    /**
     * The plan for {@code state}: Statistics is offered only when a contributed source returned a section, and a view
     * not offered (or unknown) reads as Overview, the tab's default.
     */
    @Nonnull
    static Plan plan(@Nonnull BookState state, boolean statistics, boolean milestones) {
        List<String> views = statistics
                ? List.of(BookState.VIEW_OVERVIEW, BookState.VIEW_BROWSE, BookState.VIEW_STATISTICS)
                : List.of(BookState.VIEW_OVERVIEW, BookState.VIEW_BROWSE);
        String view = views.contains(state.view()) ? state.view() : BookState.VIEW_OVERVIEW;
        return new Plan(views, view, state.selectedId(), milestones);
    }

    /** A category tile's click: Browse on that category, every status, no search; the Feats tile: Browse on Feats. */
    @Nonnull
    static BookState fromTile(@Nonnull BookState state, @Nonnull String tileId) {
        BookState next = AchievementOverview.FEATS_TILE.equals(tileId)
                ? state.withFilters(BookState.ALL, BrowseFilter.STATUS_FEATS, null, "", null)
                : state.withFilters(tileId, BookState.ALL, null, "", null);
        return next.withView(BookState.VIEW_BROWSE);
    }

    /** A status segment's click, and the hero's Show (Waiting): Browse on that status. */
    @Nonnull
    static BookState toStatus(@Nonnull BookState state, @Nonnull String status) {
        return state.withFilters(null, status, null, null, null).withView(BookState.VIEW_BROWSE);
    }

    /** A search from the overview (the state already holds the live text): Browse searching for it. */
    @Nonnull
    static BookState toSearch(@Nonnull BookState state) {
        return state.withView(BookState.VIEW_BROWSE);
    }

    /** A strip row's click: Browse on that row, every filter dropped so the row is in the list. */
    @Nonnull
    static BookState toRow(@Nonnull BookState state, @Nonnull String id) {
        return state.clearFilters().withSelected(id).withView(BookState.VIEW_BROWSE);
    }

    /** The sort dropdown's value: always one of {@link BrowseFilter#SORTS}, so the dropdown never shows blank. */
    @Nonnull
    static String sortValue(@Nonnull BookState state) {
        return BrowseFilter.of(state).sort();
    }

    /** The status segment that shows as chosen: an unknown status reads as All. */
    @Nonnull
    static String statusValue(@Nonnull BookState state) {
        return BrowseFilter.of(state).status();
    }

    /** The words of a sort, a key of the book's lang file. */
    @Nonnull
    static String sortKey(@Nonnull String sort) {
        return switch (sort) {
            case BrowseFilter.SORT_AZ -> "book.achievements.sort.alpha";
            case BrowseFilter.SORT_CLOSEST -> "book.achievements.sort.closest";
            default -> "book.achievements.sort.default";
        };
    }

    /** The words of a status segment, a key of the book's lang file. */
    @Nonnull
    static String statusKey(@Nonnull String status) {
        return switch (status) {
            case BrowseFilter.STATUS_PROGRESS -> "book.achievements.status.progress";
            case BrowseFilter.STATUS_EARNED -> "book.achievements.status.earned";
            case BrowseFilter.STATUS_WAITING -> "book.achievements.status.waiting";
            case BrowseFilter.STATUS_FEATS -> "book.achievements.status.feats";
            default -> "book.achievements.status.all";
        };
    }

    /**
     * {@code model} with each section reaching as far as asked: the rows a player asked for with "Show N more", and
     * always far enough to show {@code selectedId}'s row (rounded up to a whole page of the cap).
     */
    @Nonnull
    static LedgerModel capped(@Nonnull LedgerModel model, @Nonnull Map<String, Integer> caps,
            @Nullable String selectedId) {
        List<LedgerSection> out = new ArrayList<>(model.sections().size());
        for (LedgerSection section : model.sections()) {
            int cap = Math.max(section.cap(), caps.getOrDefault(section.id(), 0));
            if (selectedId != null) {
                int at = indexOf(section, selectedId);
                if (at >= cap) {
                    cap = (at / LedgerSection.DEFAULT_CAP + 1) * LedgerSection.DEFAULT_CAP;
                }
            }
            out.add(cap == section.cap() ? section
                    : new LedgerSection(section.id(), section.label(), section.rows(), section.openByDefault(), cap));
        }
        return new LedgerModel(out, model.firstSelectable());
    }

    /** How far a section reaches once its "Show N more" is pressed: the N its row promised, never past the end. */
    static int nextCap(@Nonnull LedgerSection section) {
        return (int) Math.min(section.rows().size(), 2L * section.cap());
    }

    /** The list's shape, section by section and row by row: a claim that leaves it equal moved nothing. */
    @Nonnull
    static List<String> structure(@Nonnull LedgerModel model) {
        List<String> out = new ArrayList<>();
        for (LedgerSection section : model.sections()) {
            out.add("section " + section.id());
            for (LedgerRow row : section.rows()) {
                out.add(row.id());
            }
        }
        return out;
    }

    /**
     * {@code view} with its Pin toggle saying what it does on hover: "Pin this achievement", or "Unpin this
     * achievement" while pinned. A view with no toggle, or another toggle, comes back as it is.
     */
    @Nullable
    static DetailView withPinTooltip(@Nullable DetailView view) {
        DetailToggle toggle = view == null ? null : view.toggle();
        if (toggle == null || !BookActions.PIN.equals(toggle.actionId())) {
            return view;
        }
        DetailToggle tipped = new DetailToggle(toggle.label(), toggle.on(), toggle.actionId(),
                text(toggle.on() ? "book.tooltip.unpin" : "book.tooltip.pin"));
        return new DetailView(view.picture(), view.title(), view.meta(), view.subMeta(), view.badges(), tipped,
                view.progress(), view.progressLabel(), view.lead(), view.blocks(), view.actions(), view.hint());
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull BookContext ctx) {
        lists.clear();
        view = null;
        shown = null;
        Subject subject = ctx.achievementSubject();
        if (subject == null) {
            paintNothing(ctx);
            return;
        }
        Reading read = new Reading(ctx, subject);
        AchievementOverview.Overview overview = read.overview();
        if (overview.total() == 0 && overview.tiles().isEmpty()) {
            ctx.header().subtitle(null);
            ctx.header().stats(List.of());
            paintNothing(ctx);
            return;
        }
        Plan plan = plan(ctx.state(), !read.statistics().isEmpty(), overview.next() != null);
        view = plan.view();
        header(ctx, overview);
        toolbar(ctx, plan, read);

        UICommandBuilder cmd = ctx.cmd();
        cmd.set(ctx.at(OVERVIEW) + ".Visible", plan.overview());
        cmd.set(ctx.at(SPLIT) + ".Visible", plan.split());
        if (plan.overview()) {
            paintOverview(ctx, overview);
            return;
        }
        // The page's buttons are live elements, bound once here: each press acts on the row the page shows then.
        DetailPainter.bindActionsOnce(ctx.events(), ctx.at(PAGE), slot -> ctx.binding(ACT, slot.name()),
                plan.browse() ? ctx.binding(BookActions.PIN) : ctx.binding(ACT, TOGGLE));
        if (plan.browse()) {
            markSeen(ctx, subject, overview, BrowseFilter.of(ctx.state()).category());
            LedgerModel model = read.reader().browse(BrowseFilter.of(ctx.state()));
            String selected = ctx.state().selectedId();
            shown = selected != null && read.reader().achievement(selected) != null
                    ? selected : model.firstSelectable();
            paintList(ctx, model, true);
        } else {
            LedgerModel model = read.statistics().model();
            String selected = ctx.state().selectedId();
            shown = selected != null && model.contains(selected) ? selected : model.firstSelectable();
            paintList(ctx, model, true);
        }
        showPage(ctx, read, shown, pageFor(read, shown));
    }

    /**
     * Browse opened on a category the player has not seen since something was earned there: mark it seen, so its
     * tile's "new" mark clears. Only a category that reads new is marked, so an open that changes nothing writes
     * nothing.
     */
    static void markSeen(@Nonnull BookContext ctx, @Nonnull Subject subject,
            @Nonnull AchievementOverview.Overview overview, @Nullable String category) {
        CollectionTile tile = unseenTile(overview.tiles(), category);
        if (tile != null) {
            ctx.deps().markSeenGuarded(subject, tile.id(), ctx.nowMs());
        }
    }

    /** The tile for {@code category} when it reads new, else null (no category, "all", or nothing new there). */
    @Nullable
    static CollectionTile unseenTile(@Nonnull List<CollectionTile> tiles, @Nullable String category) {
        if (category == null || category.isBlank() || BookState.ALL.equalsIgnoreCase(category)) {
            return null;
        }
        for (CollectionTile tile : tiles) {
            if (tile.id().equalsIgnoreCase(category.trim())) {
                return tile.unseen() ? tile : null;
            }
        }
        return null;
    }

    /** Nobody to read for, or nothing to list: one empty state across the tab, inside its own document. */
    private static void paintNothing(@Nonnull BookContext ctx) {
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(ctx.at(TOOLBAR) + ".Visible", false);
        cmd.set(ctx.at(EMPTY) + ".Visible", true);
        EmptyStatePainter.paint(cmd, ctx.events(), ctx.at(EMPTY), new EmptyState(icon(),
                ctx.text("book.empty.achievements"), null, null), null);
    }

    /** The shell's header band: the subtitle, earned of total, the points, and the next milestone with a ladder. */
    private static void header(@Nonnull BookContext ctx, @Nonnull AchievementOverview.Overview overview) {
        ctx.header().subtitle(overview.rewardsWaiting() > 0
                ? ctx.text("book.achievements.subtitle_waiting", overview.earned(), overview.total(),
                        overview.rewardsWaiting())
                : ctx.text("book.achievements.subtitle", overview.earned(), overview.total()));
        List<Stat> stats = new ArrayList<>(3);
        stats.add(new Stat(KitText.count(overview.earned(), overview.total()), ctx.text("book.achievements.stat.earned"),
                Tone.NEUTRAL));
        stats.add(new Stat(Msg.num(overview.points()), ctx.text("book.achievements.stat.points"), Tone.COLLECT));
        AchievementOverview.MilestoneCard next = overview.next();
        if (next != null) {
            stats.add(new Stat(next.allCollected()
                    ? ctx.text("book.achievements.all_milestones_claimed")
                    : KitText.count(overview.points(), next.threshold()),
                    ctx.text("book.achievements.stat.next"), Tone.NEUTRAL));
        }
        ctx.header().stats(stats);
    }

    /** The view segments and the search row; on Browse, the status segments and the two dropdowns. */
    private static void toolbar(@Nonnull BookContext ctx, @Nonnull Plan plan, @Nonnull Reading read) {
        UICommandBuilder cmd = ctx.cmd();
        UIEventBuilder events = ctx.events();
        for (String v : plan.views()) {
            SegmentPainter.append(cmd, events, ctx.at(VIEWS), ctx.text("book.view." + v), v.equals(plan.view()),
                    false, false, ctx.binding(BookActions.VIEW).append(BookState.KEY_VIEW, v));
        }
        cmd.set(ctx.at(SEARCH) + ".Visible", plan.search());
        if (plan.search()) {
            ZigSearchRow.wire(cmd, events, ctx.at(SEARCH), ctx.state().search(), BookState.KEY_SEARCH_INPUT,
                    ctx.binding(BookActions.SEARCH), ctx.binding(BookActions.CLEAR_SEARCH));
        }
        cmd.set(ctx.at(FILTERS) + ".Visible", plan.filters());
        if (!plan.filters()) {
            return;
        }
        String status = statusValue(ctx.state());
        for (String s : BrowseFilter.STATUSES) {
            SegmentPainter.append(cmd, events, ctx.at(STATUSES), ctx.text(statusKey(s)), s.equals(status), false,
                    false, ctx.binding(BookActions.STATUS, s));
        }

        // A dropdown entry is a String sink the client resolves only from a key: a translated name goes as its
        // key, a name nothing translates as its plain words.
        String category = BrowseFilter.of(ctx.state()).category();
        String chosen = BookState.ALL;
        List<DropdownEntryInfo> categories = new ArrayList<>();
        categories.add(new DropdownEntryInfo(LocalizableString.fromMessageId(key("book.achievements.category.all")),
                BookState.ALL));
        for (CollectionTile tile : read.reader().tiles()) {
            if (AchievementOverview.FEATS_TILE.equals(tile.id())) {
                continue;
            }
            categories.add(new DropdownEntryInfo(entryLabel(tile.name()), tile.id()));
            if (tile.id().equalsIgnoreCase(category)) {
                chosen = tile.id();
            }
        }
        dropdown(ctx, CATEGORY, categories, chosen, BookActions.CATEGORY);

        List<DropdownEntryInfo> sorts = new ArrayList<>(BrowseFilter.SORTS.size());
        for (String sort : BrowseFilter.SORTS) {
            sorts.add(new DropdownEntryInfo(LocalizableString.fromMessageId(key(sortKey(sort))), sort));
        }
        dropdown(ctx, SORT, sorts, sortValue(ctx.state()), BookActions.SORT);
    }

    private static void dropdown(@Nonnull BookContext ctx, @Nonnull String id, @Nonnull List<DropdownEntryInfo> entries,
            @Nonnull String value, @Nonnull String action) {
        String selector = ctx.at(id);
        ctx.cmd().set(selector + ".Entries", entries);
        ctx.cmd().set(selector + ".Value", value);
        ctx.events().addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                ctx.binding(action).append("@DropdownValue", selector + ".Value"), false);
    }

    @Nonnull
    private static LocalizableString entryLabel(@Nonnull Message name) {
        return UiText.isClientResolvable(name) ? LocalizableString.fromMessageId(name.getMessageId())
                : LocalizableString.fromString(UiText.flatten(name));
    }

    // ==================== the overview ====================

    private void paintOverview(@Nonnull BookContext ctx, @Nonnull AchievementOverview.Overview overview) {
        UICommandBuilder cmd = ctx.cmd();
        UIEventBuilder events = ctx.events();
        hero(ctx, overview);
        events.addEventBinding(CustomUIEventBindingType.Activating, ctx.at(HERO_SHOW),
                ctx.binding(BookActions.STATUS, BrowseFilter.STATUS_WAITING));
        milestone(ctx, overview.next(), overview.points());
        // Bound once, with no threshold: a press collects the rung the card shows at that moment.
        events.addEventBinding(CustomUIEventBindingType.Activating, ctx.at(MS_COLLECT),
                ctx.binding(BookActions.CLAIM_MILESTONE));

        cmd.set(ctx.at(CATEGORIES_LABEL) + ".TextSpans", ctx.text("book.achievements.overview.categories"));
        TilePainter.collection(cmd, events, ctx.at(TILES), overview.tiles(),
                tile -> ctx.binding(BookActions.CATEGORY, tile.id()));

        strip(ctx, PINNED_LIST, PINNED_EMPTY, "ov.pinned", "book.achievements.overview.pinned", overview.pinned(),
                ctx.text("book.achievements.overview.pinned_empty", maxPinned()));
        strip(ctx, RECENT_LIST, RECENT_EMPTY, "ov.recent", "book.achievements.overview.recent", overview.recent(),
                ctx.text("book.achievements.overview.recent_empty"));
        strip(ctx, NEARLY_LIST, NEARLY_EMPTY, "ov.nearly", "book.achievements.overview.nearly", overview.nearly(),
                ctx.text("book.achievements.overview.nearly_empty"));
    }

    /** The hero card, painted both ways so a partial repaint is clean. */
    private static void hero(@Nonnull BookContext ctx, @Nonnull AchievementOverview.Overview overview) {
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(ctx.at(HERO_EARNED) + ".TextSpans", KitText.count(overview.earned(), overview.total()));
        cmd.set(ctx.at(HERO_POINTS) + ".TextSpans", ctx.text("book.achievements.page.points", overview.points()));
        Progress completion = overview.completion();
        float fraction = completion == null ? 0f : completion.fraction();
        cmd.set(ctx.at(HERO_BAR) + ".Value", fraction);
        cmd.set(ctx.at(HERO_PERCENT) + ".TextSpans", KitText.percent(Math.round(fraction * 100f)));
        boolean waiting = overview.rewardsWaiting() > 0;
        cmd.set(ctx.at(HERO_WAITING) + ".Visible", waiting);
        if (waiting) {
            cmd.set(ctx.at(HERO_WAITING_LINE) + ".TextSpans",
                    ctx.text("book.achievements.hero.waiting", overview.rewardsWaiting()));
            ZigRichButton.text(cmd, ctx.at(HERO_SHOW), ctx.text("book.achievements.hero.show"));
        }
    }

    /**
     * The next-milestone card, hidden without a ladder; painted both ways so a partial repaint is clean. With a
     * ladder of two rungs or more its track shows the rungs along the bar, and the bar reads on the ladder's scale.
     */
    private static void milestone(@Nonnull BookContext ctx, @Nullable AchievementOverview.MilestoneCard card,
            long points) {
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(ctx.at(MILESTONE) + ".Visible", card != null);
        if (card == null) {
            return;
        }
        cmd.set(ctx.at(MS_TITLE) + ".TextSpans", card.title());
        boolean track = MilestoneTrack.paint(cmd, ctx.at(TRACK), ctx.at(RUNGS), card.thresholds(), points,
                card.allCollected() ? -1 : card.threshold());
        cmd.set(ctx.at(MS_BAR) + ".Value",
                track ? MilestoneTrack.barFraction(card.thresholds(), points) : card.progress().fraction());
        cmd.set(ctx.at(MS_COUNT) + ".TextSpans", KitText.count(card.progress().current(), card.progress().total()));
        optional(cmd, ctx.at(MS_REWARDS), card.rewards());
        optional(cmd, ctx.at(MS_AFTER), card.after());
        cmd.set(ctx.at(MS_COLLECT) + ".Visible", card.claimable());
        if (card.claimable()) {
            ZigRichButton.text(cmd, ctx.at(MS_COLLECT), ctx.text("book.achievements.action.collect"));
        }
    }

    /** One overview strip: a one-section list of compact rows, or its line when it has none. */
    private void strip(@Nonnull BookContext ctx, @Nonnull String list, @Nonnull String empty,
            @Nonnull String sectionId, @Nonnull String labelKey, @Nonnull List<LedgerRow> rows,
            @Nonnull Message emptyLine) {
        LedgerModel model = LedgerModel.of(List.of(new LedgerSection(sectionId, ctx.text(labelKey), rows, true)));
        paint(ctx, list, model, null, true, RowSize.COMPACT);
        ctx.cmd().set(ctx.at(empty) + ".Visible", rows.isEmpty());
        if (rows.isEmpty()) {
            ctx.cmd().set(ctx.at(empty) + ".TextSpans", emptyLine);
        }
    }

    // ==================== the list and the page ====================

    /**
     * Browse's or Statistics' list, or its empty state. In a build the empty state is painted (hidden or shown) with
     * its Clear filters bound, since a partial may not bind it; a partial repaint only shows or hides it.
     */
    private void paintList(@Nonnull BookContext ctx, @Nonnull LedgerModel model, boolean building) {
        UICommandBuilder cmd = ctx.cmd();
        boolean empty = model.isEmpty();
        cmd.set(ctx.at(LIST) + ".Visible", !empty);
        cmd.set(ctx.at(LIST_EMPTY) + ".Visible", empty);
        if (building) {
            boolean filtered = BookState.VIEW_BROWSE.equals(view) && ctx.state().anyFilter();
            EmptyState state = filtered
                    ? new EmptyState(icon(), KitText.nothingMatches(), KitText.nothingMatchesLine(),
                            new DetailAction(ActionSlot.PRIMARY, KitText.clearFilters(), ActionLook.NORMAL,
                                    BookActions.CLEAR_FILTERS, null, true, null))
                    : new EmptyState(icon(), ctx.text("book.achievements.empty"), null, null);
            EmptyStatePainter.paint(cmd, ctx.events(), ctx.at(LIST_EMPTY), state,
                    filtered ? ctx.binding(BookActions.CLEAR_FILTERS) : null);
        }
        if (empty) {
            lists.remove(LIST);
            return;
        }
        paint(ctx, LIST, model, shown, false, RowSize.STANDARD);
    }

    /** Paint one list whole (in a build, or a partial that repaints it), remembering where everything went. */
    private void paint(@Nonnull BookContext ctx, @Nonnull String list, @Nonnull LedgerModel model,
            @Nullable String selected, boolean opens, @Nonnull RowSize size) {
        Set<String> open = openSections(ctx, list);
        LedgerModel capped = capped(model, caps, selected);
        LedgerIndex index = LedgerPainter.paint(ctx.cmd(), ctx.events(), ctx.at(list), capped, open, selected,
                bindings(ctx, opens, open), size, ctx.viewer());
        lists.put(list, new Painted(model, capped, size, opens, index));
    }

    /**
     * The sections the player opened or closed, as the kit reads them. While Browse searches every section opens
     * (the reader opens them all), so the hand toggles from before the search are set aside for the list.
     */
    @Nonnull
    private Set<String> openSections(@Nonnull BookContext ctx, @Nonnull String list) {
        if (LIST.equals(list) && BookState.VIEW_BROWSE.equals(view) && BrowseFilter.of(ctx.state()).searching()) {
            return Set.of();
        }
        return ctx.state().openSections();
    }

    /** A list's clicks: a row selects (Browse, Statistics) or opens Browse on it (the overview's strips). */
    @Nonnull
    private static LedgerBindings bindings(@Nonnull BookContext ctx, boolean opens, @Nonnull Set<String> open) {
        return new LedgerBindings() {
            @Nullable
            @Override
            public EventData row(LedgerSection s, LedgerRow r) {
                return ctx.binding(opens ? BookActions.OPEN : BookActions.SELECT, r.id());
            }

            @Nullable
            @Override
            public EventData section(LedgerSection s) {
                return ctx.sectionBinding(s.id(), LedgerPainter.isOpen(s, open));
            }

            @Nullable
            @Override
            public EventData showMore(LedgerSection s) {
                return ctx.binding(MORE).append(BookState.KEY_SECTION, s.id());
            }
        };
    }

    /** The page for {@code rowId} in the painted view: an achievement's, or a statistics source's. */
    @Nullable
    private DetailView pageFor(@Nonnull Reading read, @Nullable String rowId) {
        if (rowId == null) {
            return null;
        }
        if (BookState.VIEW_STATISTICS.equals(view)) {
            return read.statistics().page(rowId, read.ledger());
        }
        Achievement achievement = read.reader().achievement(rowId);
        return achievement == null ? null : withPinTooltip(read.reader().page(achievement));
    }

    /** The page, or its empty state; both ways, so a partial repaint is clean. */
    private void showPage(@Nonnull BookContext ctx, @Nonnull Reading read, @Nullable String rowId,
            @Nullable DetailView page) {
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(ctx.at(PAGE) + ".Visible", page != null);
        cmd.set(ctx.at(PAGE_EMPTY) + ".Visible", page == null);
        if (page != null) {
            DetailPainter.paint(cmd, ctx.events(), ctx.at(PAGE), page, detailBindings(ctx, read, rowId),
                    ctx.viewer());
        } else {
            EmptyStatePainter.paint(cmd, ctx.events(), ctx.at(PAGE_EMPTY), new EmptyState(icon(),
                    ctx.text("book.achievements.page.empty"), null, null), null);
        }
    }

    /** A page line that names another row selects it; a statistics line names a row of the same source. */
    @Nonnull
    private DetailBindings detailBindings(@Nonnull BookContext ctx, @Nonnull Reading read, @Nullable String rowId) {
        boolean statistics = BookState.VIEW_STATISTICS.equals(view);
        return new DetailBindings() {
            @Nullable
            @Override
            public EventData line(DetailBlock b, DetailLine l) {
                String target = l.selectId();
                if (target == null || target.isBlank()) {
                    return null;
                }
                if (statistics) {
                    LedgerSource source = read.statistics().sourceOf(rowId);
                    if (source == null) {
                        return null;
                    }
                    target = StatisticsView.id(source, target);
                }
                return ctx.binding(BookActions.SELECT, target);
            }

            @Nullable
            @Override
            public EventData toggle(DetailToggle t) {
                return null;
            }
        };
    }

    // ==================== events ====================

    @Override
    public boolean handle(@Nonnull BookContext ctx, @Nonnull String action, @Nonnull ObjectiveBookEventData data) {
        BookState state = ctx.state();
        String painted = view != null ? view : state.view();
        switch (action) {
            case BookActions.SELECT:
                return select(ctx, data.id);
            case BookActions.SECTION:
                return section(ctx, firstNonBlank(data.section, data.id));
            case MORE:
                return more(ctx, firstNonBlank(data.section, data.id));
            case BookActions.CATEGORY: {
                // A tile (only the overview has them) opens Browse; Browse's dropdown is the shell's.
                String tile = firstNonBlank(data.id, data.dropdownValue);
                if (!BookState.VIEW_OVERVIEW.equals(painted) || tile == null) {
                    return false;
                }
                ctx.reopen(fromTile(state, tile));
                return true;
            }
            case BookActions.STATUS: {
                String status = firstNonBlank(data.id, data.dropdownValue);
                ctx.reopen(toStatus(state, status == null ? BookState.ALL : status));
                return true;
            }
            case BookActions.SEARCH:
                if (BookState.VIEW_BROWSE.equals(painted)) {
                    return false;
                }
                ctx.reopen(toSearch(state));
                return true;
            case BookActions.OPEN: {
                String id = firstNonBlank(data.id, null);
                boolean otherTab = data.tab != null && !data.tab.isBlank()
                        && !ObjectiveBookPage.TAB_ACHIEVEMENTS.equalsIgnoreCase(data.tab.trim());
                if (id == null || otherTab) {
                    return false;
                }
                ctx.reopen(toRow(state, id));
                return true;
            }
            case BookActions.PIN:
                return pin(ctx);
            case BookActions.CLAIM:
                return claim(ctx);
            case BookActions.CLAIM_MILESTONE:
                return claimMilestone(ctx, data.threshold);
            case ACT:
                return act(ctx, data.id);
            default:
                return false;
        }
    }

    /** A row, or a page line naming one: the selection moves and the page repaints, the scroll kept. */
    private boolean select(@Nonnull BookContext ctx, @Nullable String raw) {
        String id = firstNonBlank(raw, null);
        Subject subject = ctx.achievementSubject();
        if (id == null || subject == null || !SPLIT_VIEWS.contains(view)) {
            return false;
        }
        Reading read = new Reading(ctx, subject);
        DetailView page = pageFor(read, id);
        if (page == null) {
            return false;
        }
        Painted list = lists.get(LIST);
        if (list != null) {
            LedgerPainter.select(ctx.cmd(), list.index(), shown, id, ctx.viewer());
        }
        shown = id;
        showPage(ctx, read, id, page);
        ctx.keep(ctx.state().withSelected(id));
        ctx.sendPartial();
        return true;
    }

    /** A section head: the section opens (its rows appended the first time) or closes in place. */
    private boolean section(@Nonnull BookContext ctx, @Nullable String id) {
        if (id == null || !SECTION_IN_PLACE) {
            return false;
        }
        for (Map.Entry<String, Painted> entry : lists.entrySet()) {
            Painted painted = entry.getValue();
            LedgerSection section = find(painted.capped(), id);
            if (section == null) {
                continue;
            }
            // The head's binding was made when the section was painted, so the index, not the event, says which
            // way a press goes now.
            boolean open = !painted.index().isOpen(id);
            if (open) {
                LedgerPainter.openSection(ctx.cmd(), ctx.events(), painted.index(), section,
                        bindings(ctx, painted.opens(), openSections(ctx, entry.getKey())));
            } else {
                LedgerPainter.closeSection(ctx.cmd(), painted.index(), id);
            }
            ctx.keep(ctx.state().withSection(id, open));
            ctx.sendPartial();
            return true;
        }
        return false;
    }

    /** "Show N more": the section reaches N further and its list repaints, the scroll kept. */
    private boolean more(@Nonnull BookContext ctx, @Nullable String id) {
        if (id == null) {
            return false;
        }
        for (Map.Entry<String, Painted> entry : lists.entrySet()) {
            Painted painted = entry.getValue();
            LedgerSection section = find(painted.capped(), id);
            if (section == null) {
                continue;
            }
            caps.put(id, nextCap(section));
            String list = entry.getKey();
            paint(ctx, list, painted.model(), LIST.equals(list) ? shown : null, painted.opens(), painted.size());
            ctx.sendPartial();
            return true;
        }
        return false;
    }

    /**
     * Pin or unpin the achievement the page shows (the verb raises the toast, the cap's included); its row moves into
     * or out of Pinned and the page's toggle flips, in place.
     */
    private boolean pin(@Nonnull BookContext ctx) {
        String id = live(ctx);
        Subject subject = ctx.achievementSubject();
        if (id == null || subject == null || !BookState.VIEW_BROWSE.equals(view)) {
            return false;
        }
        ctx.verbs().togglePin(id);
        Reading read = new Reading(ctx, subject);
        repaintBrowse(ctx, read, read.reader().browse(BrowseFilter.of(ctx.state())), id);
        ctx.keep(ctx.state().withSelected(id));
        ctx.sendPartial();
        return true;
    }

    /**
     * Collect the rewards of the achievement the page shows. When no row moves, the list, the page and the header
     * repaint in place; when one does (it leaves Waiting), the book reopens on the same state, the row still chosen.
     */
    private boolean claim(@Nonnull BookContext ctx) {
        String id = live(ctx);
        Subject subject = ctx.achievementSubject();
        if (id == null || subject == null || !BookState.VIEW_BROWSE.equals(view)) {
            return false;
        }
        BrowseFilter filter = BrowseFilter.of(ctx.state());
        Reading before = new Reading(ctx, subject);
        Achievement achievement = before.reader().achievement(id);
        if (achievement == null) {
            return false;
        }
        List<String> was = structure(before.reader().browse(filter));
        if (!ctx.verbs().claim(achievement)) {
            ctx.sendPartial();
            return true;
        }
        Reading after = new Reading(ctx, subject);
        LedgerModel model = after.reader().browse(filter);
        if (!structure(model).equals(was)) {
            ctx.reopen(ctx.state().withSelected(id));
            return true;
        }
        repaintBrowse(ctx, after, model, id);
        header(ctx, after.overview());
        ctx.keep(ctx.state().withSelected(id));
        ctx.sendPartial();
        return true;
    }

    /** The milestone card's Collect: the rung it shows now (or the event's own threshold), then the card repaints. */
    private boolean claimMilestone(@Nonnull BookContext ctx, @Nullable String rawThreshold) {
        Subject subject = ctx.achievementSubject();
        if (subject == null || !BookState.VIEW_OVERVIEW.equals(view)) {
            return false;
        }
        int threshold = threshold(rawThreshold);
        if (threshold <= 0) {
            AchievementOverview.MilestoneCard next = new Reading(ctx, subject).overview().next();
            if (next == null || !next.claimable()) {
                ctx.sendPartial();
                return true;
            }
            threshold = next.threshold();
        }
        ctx.verbs().claimMilestone(threshold);
        AchievementOverview.Overview overview = new Reading(ctx, subject).overview();
        milestone(ctx, overview.next(), overview.points());
        hero(ctx, overview);
        header(ctx, overview);
        ctx.sendPartial();
        return true;
    }

    /** A page button, dispatched on what the page shows now: a destination opens, Collect claims, Pin pins. */
    private boolean act(@Nonnull BookContext ctx, @Nullable String slotName) {
        String id = live(ctx);
        Subject subject = ctx.achievementSubject();
        if (id == null || subject == null || slotName == null || !SPLIT_VIEWS.contains(view)) {
            return false;
        }
        DetailView page = pageFor(new Reading(ctx, subject), id);
        if (page == null) {
            return false;
        }
        String actionId = null;
        if (TOGGLE.equals(slotName)) {
            DetailToggle toggle = page.toggle();
            actionId = toggle == null ? null : toggle.actionId();
        } else {
            ActionSlot slot = slot(slotName);
            DetailAction action = slot == null ? null : page.action(slot);
            if (action != null && action.enabled()) {
                if (action.destination() != null && ctx.openDestination(action.destination())) {
                    return true;
                }
                actionId = action.actionId();
            }
        }
        if (BookActions.CLAIM.equals(actionId)) {
            return claim(ctx);
        }
        if (BookActions.PIN.equals(actionId)) {
            return pin(ctx);
        }
        ctx.sendPartial();
        return true;
    }

    /** Browse's list and page repainted in a partial update. */
    private void repaintBrowse(@Nonnull BookContext ctx, @Nonnull Reading read, @Nonnull LedgerModel model,
            @Nonnull String id) {
        shown = id;
        paintList(ctx, model, false);
        showPage(ctx, read, id, pageFor(read, id));
    }

    /** The row the page shows, which every page button acts on. */
    @Nullable
    private String live(@Nonnull BookContext ctx) {
        return shown != null ? shown : ctx.state().selectedId();
    }

    // ==================== helpers ====================

    /** What one answer reads, each part read once and only when asked for. */
    private static final class Reading {

        private final BookContext ctx;
        private final Subject subject;
        private AchievementReader reader;
        private List<MilestoneView> ladder;
        private StatisticsView statistics;
        private LedgerContext ledger;
        private boolean ledgerRead;

        Reading(@Nonnull BookContext ctx, @Nonnull Subject subject) {
            this.ctx = ctx;
            this.subject = subject;
        }

        @Nonnull
        AchievementReader reader() {
            if (reader == null) {
                UUID viewer = ctx.viewer() == null ? null : ctx.viewer().getUuid();
                reader = AchievementReader.of(ProgressionRuntime.achievements(), subject, ctx.deps(), viewer,
                        Occurrences.source(), ctx.deps().seen(), ctx.nowMs());
            }
            return reader;
        }

        /** The consumer's milestone ladder; none without a store to read it from. */
        @Nonnull
        List<MilestoneView> ladder() {
            if (ladder == null) {
                ladder = ctx.store() == null || ctx.ref() == null ? List.of()
                        : ctx.deps().milestonesGuarded(ctx.store(), ctx.ref(), subject);
            }
            return ladder;
        }

        @Nonnull
        AchievementOverview.Overview overview() {
            return reader().overview(AchievementReader.RECENT, AchievementReader.NEARLY, ladder());
        }

        /** Who a contributed source reads for; null without a player behind the page. */
        @Nullable
        LedgerContext ledger() {
            if (!ledgerRead) {
                ledgerRead = true;
                if (ctx.store() != null && ctx.ref() != null && ctx.viewer() != null) {
                    ledger = new LedgerContext(ctx.store(), ctx.ref(), ctx.viewer(), ctx.nowMs());
                }
            }
            return ledger;
        }

        @Nonnull
        StatisticsView statistics() {
            if (statistics == null) {
                try {
                    statistics = StatisticsView.read(ledger());
                } catch (Throwable t) {
                    SafeLog.warn("[progression] the book could not read its statistics: " + t.getMessage());
                    statistics = StatisticsView.EMPTY;
                }
            }
            return statistics;
        }
    }

    /**
     * One painted list: the model as read (so "Show N more" repaints from it), the model as shown (with its caps),
     * the row template, whether a row opens Browse rather than selecting, and where everything went.
     */
    private record Painted(@Nonnull LedgerModel model, @Nonnull LedgerModel capped, @Nonnull RowSize size,
            boolean opens, @Nonnull LedgerIndex index) {
    }

    @Nullable
    private static LedgerSection find(@Nonnull LedgerModel model, @Nonnull String id) {
        for (LedgerSection section : model.sections()) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        return null;
    }

    private static int indexOf(@Nonnull LedgerSection section, @Nonnull String rowId) {
        List<LedgerRow> rows = section.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(rowId)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static ActionSlot slot(@Nonnull String name) {
        for (ActionSlot slot : ActionSlot.values()) {
            if (slot.name().equalsIgnoreCase(name.trim())) {
                return slot;
            }
        }
        return null;
    }

    private static int threshold(@Nullable String raw) {
        try {
            return raw == null || raw.isBlank() ? -1 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int maxPinned() {
        try {
            return ProgressionRuntime.achievements().maxPinned();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void optional(@Nonnull UICommandBuilder cmd, @Nonnull String label, @Nullable Message text) {
        if (text != null) {
            cmd.set(label + ".TextSpans", text);
        }
        cmd.set(label + ".Visible", text != null);
    }

    @Nonnull
    private static Picture icon() {
        return Picture.item(ObjectiveBookMenu.ACHIEVEMENTS_ICON);
    }

    /** A key of the book's lang file as the client registers it. */
    @Nonnull
    private static String key(@Nonnull String key) {
        return PREFIX + DOMAIN + key;
    }

    /** A key of the book's lang file as a message. */
    @Nonnull
    private static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
