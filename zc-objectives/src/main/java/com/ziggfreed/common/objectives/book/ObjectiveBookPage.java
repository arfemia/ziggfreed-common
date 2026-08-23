package com.ziggfreed.common.objectives.book;

import java.util.HashSet;
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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.hud.TrackedQuestPanelRenderer;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.StatusTones;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Objective Book: the full-screen two-tab progression menu (quests, achievements) over THE
 * shared progression runtime. The quests tab is the quest log - category / search / tag / status
 * filters, a pre-expanded Active section over the browse list, per-row expand, accept / claim /
 * hand-in / abandon / track, and the tracked-quests side panel. The achievements tab is a
 * two-panel browser - a filter strip and compact rows on the left, the selected achievement's
 * whole story on the right, and an overview (recent, nearest, pinned, categories, milestones)
 * filling the right panel while nothing is selected.
 *
 * <p>It reads the one merged catalogue, whoever authored each entry, and every mutating call is
 * wrapped in the registered {@link ProgressionCallScope} - so a quest claimed here fires exactly
 * what the owning mod's own menu would have fired. What the library cannot know - a consumer's
 * menu rail, its board-managed quests, its milestone ladder, who claimed a server-first - rides
 * {@link ObjectiveBookDeps}, and every seam's default leaves the book working on a bare server.
 *
 * <p><b>State model.</b> The FILTER state is stateless across events: every binding round-trips
 * the full next state (the live search text captured on every one), and {@link #handleDataEvent}
 * reopens the page with it. Two things are per-instance UI memory instead, the way the NPC quest
 * page keeps its selection: which browse rows are expanded, and which achievement is selected -
 * both exist so a partial update can answer a click without asking the client what it shows, and
 * both are threaded into the reopened instance so a filter click does not lose them. EVERY exit
 * path sends a response; one that does not leaves the client spinning forever.
 */
public final class ObjectiveBookPage extends ToastablePage<ObjectiveBookEventData> {

    /** The quests tab id, also the default. */
    public static final String TAB_QUESTS = "quests";

    /** The achievements tab id. */
    public static final String TAB_ACHIEVEMENTS = "achievements";

    static final String PAGE_TEMPLATE = "Pages/ZigObjectiveBookPage.ui";
    static final String QUEST_ROW_TEMPLATE = "Pages/ZigQuestLogRow.ui";
    static final String OBJECTIVE_ROW_TEMPLATE = "Pages/ZigBookObjectiveRow.ui";
    static final String TAG_CHIP_TEMPLATE = "Pages/ZigBookTagChip.ui";
    static final String CAT_TAB_TEMPLATE = "Pages/ZigBookCatTab.ui";
    static final String WIDE_TAB_TEMPLATE = "Pages/ZigBookWideTab.ui";
    static final String ACH_ROW_TEMPLATE = "Pages/ZigAchListRow.ui";
    static final String ACH_CHIP_TEMPLATE = "Pages/ZigAchChipRow.ui";
    static final String ACH_CRITERION_TEMPLATE = "Pages/ZigAchCriterionRow.ui";
    static final String ACH_CATEGORY_CARD_TEMPLATE = "Pages/ZigAchCategoryCard.ui";
    static final String MILESTONE_TEMPLATE = "Pages/ZigMilestoneCard.ui";
    static final String REWARD_ROW_TEMPLATE = "Pages/ZigBookRewardRow.ui";

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.progression.lang} file). */
    private static final String DOMAIN = "progression.";

    /** A hard ceiling on rendered rows per list, so a huge catalogue cannot build an unbounded page. */
    static final int MAX_ROWS = 200;

    /** Category chips beyond this collapse into the native dropdown instead of overflowing the strip. */
    static final int MAX_CATEGORY_CHIPS = 6;

    // The book's own tab strip contrast, shared with the leaderboard page's treatment.
    private static final String TAB_ACTIVE_TINT = "#5e86bd";
    private static final String TAB_ACTIVE_HOVER = "#6f97cf";
    private static final String TAB_ACTIVE_TEXT = "#ffffff";
    private static final String TAB_INACTIVE_TINT = "#2f3b49";
    private static final String TAB_INACTIVE_HOVER = "#445364";
    private static final String TAB_INACTIVE_TEXT = "#9fb0c2";

    /** The selected achievement row's tint, and the resting row fill it swaps against. */
    static final String ROW_SELECTED_TINT = "#1a2d44";
    static final String ROW_TINT = "#1a2233";

    /** The category / status / sort value meaning "no filter"; never rendered, only compared. */
    static final String FILTER_ALL = "all";

    private final String tab;
    @Nonnull private final String filterCategory;
    @Nonnull private final String filterStatus;
    @Nonnull private final String searchText;
    @Nonnull private final String filterTag;
    @Nonnull private final String filterSubcategory;
    @Nonnull private final String sortMode;

    /** The achievement the detail column shows; per-instance, threaded through reopens. */
    @Nullable private String selectedId;

    /** The selected list row's selector, so a partial selection swap can untint the old row. */
    @Nullable private String selectedRowSel;

    /** Which browse rows are open; per-instance UI memory, exactly what a partial toggle needs. */
    private final Set<String> expandedQuestIds = new HashSet<>();

    /** The deps resolved for THIS open, so build and its partial updates read one consistent set. */
    @Nonnull private ObjectiveBookDeps deps = ObjectiveBookDeps.DEFAULTS;

    /** Open the book on the quests tab. */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef) {
        this(playerRef, TAB_QUESTS);
    }

    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nullable String tab) {
        this(playerRef, tab, null, null, null, null);
    }

    /** The quests-tab full-state form; null filters mean "off". */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nullable String tab,
                             @Nullable String filterCategory, @Nullable String filterStatus,
                             @Nullable String searchText, @Nullable String filterTag) {
        this(playerRef, tab, filterCategory, filterStatus, searchText, filterTag, null, null, null);
    }

    /** The whole-state form every filter binding round-trips. */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nullable String tab,
                             @Nullable String filterCategory, @Nullable String filterStatus,
                             @Nullable String searchText, @Nullable String filterTag,
                             @Nullable String filterSubcategory, @Nullable String sortMode,
                             @Nullable String selectedId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction,
                ObjectiveBookEventData.CODEC);
        this.tab = TAB_ACHIEVEMENTS.equals(tab) ? TAB_ACHIEVEMENTS : TAB_QUESTS;
        this.filterCategory = normalizeFilter(filterCategory);
        this.filterStatus = normalizeFilter(filterStatus);
        this.searchText = searchText == null ? "" : searchText.trim();
        this.filterTag = filterTag == null ? "" : filterTag.trim();
        this.filterSubcategory = filterSubcategory == null ? "" : filterSubcategory.trim();
        this.sortMode = sortMode == null || sortMode.isBlank() ? "" : sortMode.trim();
        this.selectedId = selectedId == null || selectedId.isBlank() ? null : selectedId.trim();
    }

    @Nonnull
    private static String normalizeFilter(@Nullable String value) {
        return value == null || value.isBlank() ? FILTER_ALL : value.trim();
    }

    // ==================== state reads for the tab renderers ====================

    @Nonnull
    String filterCategory() {
        return filterCategory;
    }

    @Nonnull
    String filterStatus() {
        return filterStatus;
    }

    @Nonnull
    String searchText() {
        return searchText;
    }

    @Nonnull
    String filterTag() {
        return filterTag;
    }

    @Nonnull
    String filterSubcategory() {
        return filterSubcategory;
    }

    @Nonnull
    String sortMode() {
        return sortMode;
    }

    @Nullable
    String selectedId() {
        return selectedId;
    }

    void rememberSelectedRow(@Nullable String rowSelector) {
        this.selectedRowSel = rowSelector;
    }

    @Nonnull
    Set<String> expandedQuestIds() {
        return expandedQuestIds;
    }

    @Nonnull
    ObjectiveBookDeps deps() {
        return deps;
    }

    @Nonnull
    PlayerRef viewer() {
        return playerRef;
    }

    /** A tab renderer's way to send a partial update; the base method is not its to call. */
    void pushUpdate(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        this.sendUpdate(cmd, events, false);
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        deps = ObjectiveBookPages.resolvedDeps();
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));

        boolean achievementsTab = TAB_ACHIEVEMENTS.equals(tab);
        cmd.set("#PanelTitle.TextSpans",
                text(achievementsTab ? "book.tab.achievements" : "book.tab.quests"));
        ZigRichButton.text(cmd, "#TabQuests", text("book.tab.quests"));
        ZigRichButton.text(cmd, "#TabAchievements", text("book.tab.achievements"));
        bindTab(events, "#TabQuests", TAB_QUESTS);
        bindTab(events, "#TabAchievements", TAB_ACHIEVEMENTS);
        styleTab(cmd, "#TabQuests", !achievementsTab);
        styleTab(cmd, "#TabAchievements", achievementsTab);

        Player player = store.getComponent(ref, Player.getComponentType());

        // Consumer chrome: the rail, and (on the achievements tab) the side column. Painted before
        // the tab body so a painter's title suppression wins over the default title above.
        if (player != null) {
            ObjectiveBookDeps.Chrome chrome = new ObjectiveBookDeps.Chrome(cmd, events, store, ref,
                    player, tab, (selector, extId) ->
                            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                                    fullState("ext").append("Id", extId), false));
            boolean railPainted = deps.paintGuarded(deps.railPainter(), chrome, "rail");
            cmd.set("#LeftPanel.Visible", railPainted);
            if (achievementsTab) {
                boolean sidePainted = deps.paintGuarded(deps.sidePanelPainter(), chrome, "side panel");
                cmd.set("#SidePanel.Visible", sidePainted);
                cmd.set("#AchSideContent.Visible", sidePainted);
            }
        } else {
            cmd.set("#LeftPanel.Visible", false);
            if (achievementsTab) {
                cmd.set("#SidePanel.Visible", false);
            }
        }

        cmd.set(achievementsTab ? "#AchievementsTab.Visible" : "#QuestsTab.Visible", true);

        QuestEngine questEngine = ProgressionRuntime.quests();
        AchievementEngine achievementEngine = ProgressionRuntime.achievements();
        // The subject comes from the runtime, never built here: with somebody else's store active,
        // a subject carrying the wrong handle reads neutral and drops every write, so an accept or
        // a claim from this page would silently do nothing at all.
        Subject subject = subjectFor(store, ref, achievementsTab);
        if (subject == null) {
            cmd.set(achievementsTab ? "#AEmptyMessage.Visible" : "#QEmptyMessage.Visible", true);
            cmd.set(achievementsTab ? "#AEmptyMessage.TextSpans" : "#QEmptyMessage.TextSpans",
                    text(achievementsTab ? "book.empty.achievements" : "book.empty.quests"));
            if (!achievementsTab) {
                cmd.set("#SidePanel.Visible", false);
            }
            renderToastInto(cmd);
            return;
        }

        // Both engines document self-heal as "whenever a surface opens": it settles a
        // standing-value step and re-offers a repeatable whose cooldown has elapsed, so the lists
        // below read current rather than one open behind.
        selfHeal(questEngine, achievementEngine, subject);

        if (achievementsTab) {
            BookAchievementsTab.render(this, cmd, events, store, ref, subject, achievementEngine);
        } else {
            cmd.set("#QuestSideContent.Visible", true);
            cmd.set("#TrackedQuestHeader.TextSpans", text("book.quests.tracked_header"));
            // Pre-allocates the engine's tracked-slot rows so a later partial update can repaint
            // them by index; rows, progress and the empty note come from the one shared panel
            // renderer, reading the same runtime the HUD reads.
            TrackedQuestPanelRenderer.render(cmd, null, subject, true, null);
            BookQuestsTab.render(this, cmd, events, subject, questEngine);
        }
        renderToastInto(cmd);
    }

    /**
     * Get the page's markup onto the screen, through the consumer's theme where there is one.
     * Guarded with the plain append as the fallback: a theme is decoration, and a decoration that
     * throws must not cost the player the whole screen. A theme that threw AFTER appending would
     * append twice, so the retry only runs when nothing landed.
     */
    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, "#LeftPanel", "#SidePanel");
            return;
        } catch (Throwable t) {
            SafeLog.warn("[progression] a page theme failed, so the objective book renders plain: "
                    + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    private void selfHeal(@Nonnull QuestEngine questEngine,
                          @Nonnull AchievementEngine achievementEngine, @Nonnull Subject subject) {
        try {
            questEngine.selfHeal(subject);
            achievementEngine.selfHeal(subject);
        } catch (Throwable t) {
            SafeLog.warn("[progression] objective book self-heal failed", t);
        }
    }

    // ==================== shared painting helpers ====================

    @Nonnull
    Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    /**
     * Every binding carries the WHOLE filter state - plus the live search-field text - so no click
     * resets what the player had narrowed a list to, and typed-but-unsubmitted search text
     * survives any action.
     */
    @Nonnull
    EventData fullState(@Nonnull String action) {
        return fullState(action, tab);
    }

    /** {@link #fullState(String)} with the tab overridden - the tab buttons' own form. */
    @Nonnull
    EventData fullState(@Nonnull String action, @Nonnull String forTab) {
        return EventData.of("Action", action)
                .append("Tab", forTab)
                .append("Category", filterCategory)
                .append("Status", filterStatus)
                .append("Search", searchText)
                .append("Tag", filterTag)
                .append("Subcategory", filterSubcategory)
                .append("Sort", sortMode)
                .append("@SearchInput", activeSearchField() + ".Value");
    }

    /** The active tab's search field, the one whose live text every binding captures. */
    @Nonnull
    private String activeSearchField() {
        return TAB_ACHIEVEMENTS.equals(tab) ? "#ASearchField" : "#QSearchField";
    }

    private void bindTab(@Nonnull UIEventBuilder events, @Nonnull String selector,
                         @Nonnull String target) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                fullState("tab", target).append("Id", ""), false);
    }

    private static void styleTab(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
                                 boolean active) {
        String base = active ? TAB_ACTIVE_TINT : TAB_INACTIVE_TINT;
        UiRetint.retintButtonStates(cmd, selector, base,
                active ? TAB_ACTIVE_HOVER : TAB_INACTIVE_HOVER, base);
        ZigRichButton.color(cmd, selector, active ? TAB_ACTIVE_TEXT : TAB_INACTIVE_TEXT);
    }

    /**
     * Active / inactive styling for an appended filter chip's {@code #CatBtn} (a {@code Button} +
     * inner {@code #Label}): the active chip's label goes accent-blue + bold; an inactive one
     * keeps its authored colour and drops bold. The pill's own hover / press stays its style's.
     */
    static void styleChipActive(@Nonnull UICommandBuilder cmd, @Nonnull String btnSelector,
                                boolean active) {
        ZigRichButton.color(cmd, btnSelector, active ? StatusTones.IN_PROGRESS.hex() : null);
        cmd.set(btnSelector + " #Label.Style.RenderBold", active);
    }

    /**
     * Paint the shared pin / track glyph button: toggle the baked {@code #PinIconOff} /
     * {@code #PinIconOn} variants and retint the pill (gold when on, steel when off). The icon is
     * baked in the template - a TexturePath pushed from Java renders a red X.
     */
    static void paintPinIcon(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
                             boolean on) {
        cmd.set(buttonSelector + " #PinIconOff.Visible", !on);
        cmd.set(buttonSelector + " #PinIconOn.Visible", on);
        if (on) {
            cmd.set(buttonSelector + ".Style.Default.Background", "#3a2d10");
            cmd.set(buttonSelector + ".Style.Hovered.Background", "#4a3a18");
            cmd.set(buttonSelector + ".Style.Pressed.Background", "#2a1d08");
        } else {
            cmd.set(buttonSelector + ".Style.Default.Background", "#1a2233");
            cmd.set(buttonSelector + ".Style.Hovered.Background", "#243144");
            cmd.set(buttonSelector + ".Style.Pressed.Background", "#0f1723");
        }
    }

    /** The gold Claim tint, shared by every collect button (row morphs included). */
    static void applyGoldClaim(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector) {
        UiRetint.retintButtonStates(cmd, buttonSelector, "#6b5a2e", "#8a7440", "#57481f");
        ZigRichButton.color(cmd, buttonSelector, "#ffcc4a");
    }

    /** The subject the ACTIVE store understands, for whichever side of the book is being read. */
    @Nullable
    private Subject subjectFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               boolean achievementsTab) {
        return achievementsTab
                ? ProgressionRuntime.subjects().achievementSubject(store, ref)
                : ProgressionRuntime.subjects().questSubject(store, ref);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ObjectiveBookEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action == null ? "" : data.action;
        if (action.isEmpty() || "close".equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        // The live field value is the search truth on EVERY action: it holds whatever the player
        // typed, seeded from the carried state, so unsubmitted text survives any click.
        String liveSearch = data.searchInput != null ? data.searchInput
                : (data.search != null ? data.search : searchText);

        switch (action) {
            case "tab" -> {
                // A tab switch is a fresh view: the two tabs speak different filter vocabularies,
                // so nothing but the tab carries across.
                open(player, ref, store, new ObjectiveBookPage(playerRef, data.tab));
            }
            case "category" -> {
                String value = firstNonBlank(data.id, data.dropdownValue, FILTER_ALL);
                // A new category resets the subcategory: the old one belongs to the old category.
                open(player, ref, store, new ObjectiveBookPage(playerRef, tab, value, filterStatus,
                        liveSearch, filterTag, null, sortMode, selectedId));
            }
            case "subfilter" -> open(player, ref, store, new ObjectiveBookPage(playerRef, tab,
                    filterCategory, filterStatus, liveSearch, filterTag,
                    data.id == null ? "" : data.id, sortMode, selectedId));
            case "status" -> open(player, ref, store, new ObjectiveBookPage(playerRef, tab,
                    filterCategory, firstNonBlank(data.id, FILTER_ALL, FILTER_ALL), liveSearch,
                    filterTag, filterSubcategory, sortMode, selectedId));
            case "sort" -> open(player, ref, store, new ObjectiveBookPage(playerRef, tab,
                    filterCategory, filterStatus, liveSearch, filterTag, filterSubcategory,
                    data.id == null ? "" : data.id, selectedId));
            case "search" -> open(player, ref, store, new ObjectiveBookPage(playerRef, tab,
                    filterCategory, filterStatus,
                    data.searchInput != null ? data.searchInput : "", filterTag,
                    filterSubcategory, sortMode, selectedId));
            case "clear_search" -> open(player, ref, store, new ObjectiveBookPage(playerRef, tab,
                    filterCategory, filterStatus, "", filterTag, filterSubcategory, sortMode,
                    selectedId));
            case "tag" -> open(player, ref, store, new ObjectiveBookPage(playerRef, tab,
                    filterCategory, filterStatus, liveSearch,
                    data.dropdownValue != null ? data.dropdownValue : "", filterSubcategory,
                    sortMode, selectedId));
            case "ext" -> {
                boolean took = false;
                try {
                    took = deps.extHandler().handle(data.id == null ? "" : data.id, store, ref, player);
                } catch (Throwable t) {
                    SafeLog.warn("[progression] the book's ext handler failed: " + t.getMessage());
                }
                if (!took) {
                    // The handler kept us on screen: answer the client so it never hangs.
                    this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
                }
            }
            case "toggle" -> handleToggle(data);
            case "primary" -> handlePrimary(ref, store, player, data, liveSearch);
            case "abandon" -> handleAbandon(ref, store, player, data, liveSearch);
            case "toggletrack" -> handleToggleTrack(ref, store, data);
            case "turn_in" -> handleTurnIn(ref, store, data);
            case "select" -> handleSelect(ref, store, data);
            case "togglepin" -> handleTogglePin(ref, store, data);
            case "claim" -> handleAchievementClaim(ref, store);
            case "claim_milestone" -> handleMilestoneClaim(ref, store, player, data);
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    private void open(@Nonnull Player player, @Nonnull Ref<EntityStore> ref,
                      @Nonnull Store<EntityStore> store, @Nonnull ObjectiveBookPage next) {
        player.getPageManager().openCustomPage(ref, store, next);
    }

    /** Reopen with the SAME state, for the paths where a partial repaint cannot tell the truth. */
    private void reopenSame(@Nonnull Player player, @Nonnull Ref<EntityStore> ref,
                            @Nonnull Store<EntityStore> store, @Nonnull String liveSearch) {
        open(player, ref, store, new ObjectiveBookPage(playerRef, tab, filterCategory, filterStatus,
                liveSearch, filterTag, filterSubcategory, sortMode, selectedId));
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String a, @Nullable String b, @Nonnull String fallback) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return fallback;
    }

    // ==================== quest actions ====================

    private void handleToggle(@Nonnull ObjectiveBookEventData data) {
        UICommandBuilder cmd = new UICommandBuilder();
        if (data.id != null && data.selector != null) {
            boolean wasExpanded = expandedQuestIds.contains(data.id);
            if (wasExpanded) {
                expandedQuestIds.remove(data.id);
            } else {
                expandedQuestIds.add(data.id);
            }
            cmd.set(data.selector + " #ExpandableContent.Visible", !wasExpanded);
            UiText.setText(cmd, data.selector + " #ExpandToggle.Text", wasExpanded ? "+" : "-");
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /**
     * The ONE state-dispatched row button: Claim when the row is ready to collect, else Accept.
     * Dispatching on the LIVE state (not a baked-in binding) is what lets a completing hand-in
     * morph Hand in into a gold Claim in place with no re-bind.
     */
    private void handlePrimary(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                               @Nonnull Player player, @Nonnull ObjectiveBookEventData data,
                               @Nonnull String liveSearch) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = engine.quest(data.id);
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        if (quest == null || subject == null) {
            reopenSame(player, ref, store, liveSearch);
            return;
        }
        ProgressionCallScope scope = ProgressionRuntime.questScope();
        if (engine.status(subject, quest) == QuestStatus.COMPLETED_UNCLAIMED) {
            Message preCheckRefusal = deps.claimPreCheckGuarded(quest, store, ref, player);
            if (preCheckRefusal != null) {
                // A consumer refusal short-circuits before the engine ever sees this claim - no
                // state change - and still answers the client so it never hangs.
                UICommandBuilder cmd = new UICommandBuilder();
                showToast(ToastKind.ERROR, preCheckRefusal);
                BookQuestsTab.updateHeaderCounts(this, cmd, subject, engine);
                this.sendUpdate(cmd, new UIEventBuilder(), false);
                return;
            }
            boolean ok = Boolean.TRUE.equals(
                    scope.around(subject, s -> Boolean.valueOf(engine.claim(s, quest))));
            UICommandBuilder cmd = new UICommandBuilder();
            if (ok) {
                showToast(ToastKind.REWARD, text("book.toast.quest_complete",
                        BookQuestsTab.nameOf(quest)));
                // The claimed quest leaves the pinned Active list. Hide its row in place - hide,
                // NOT remove, so the sibling #ActiveQuestList[i] selectors do not drift - and
                // refresh the counts + the tracked panel.
                if (data.selector != null) {
                    cmd.set(data.selector + ".Visible", false);
                }
                BookQuestsTab.updateHeaderCounts(this, cmd, subject, engine);
                TrackedQuestPanelRenderer.render(cmd, null, subject, false, null);
            } else {
                // The engine refuses a placeless payout for a site-bound quest: say where instead
                // of failing silently. Either way the client gets a definite response.
                showToast(ToastKind.ERROR, text(quest.turnInAt() != null
                        ? "book.quests.claim_at_site" : "book.toast.claim_failed"));
                BookQuestsTab.updateHeaderCounts(this, cmd, subject, engine);
            }
            this.sendUpdate(cmd, new UIEventBuilder(), false);
            return;
        }

        // ACCEPT (not started). Cannot accept any more (cap reached, prerequisites moved) - a full
        // repaint re-renders the row through the corrected branch.
        if (!engine.canAccept(subject, quest).allowed()) {
            reopenSame(player, ref, store, liveSearch);
            return;
        }
        boolean ok = Boolean.TRUE.equals(scope.around(subject, s -> {
            boolean accepted = engine.canAccept(s, quest).allowed() && engine.accept(s, quest);
            if (accepted) {
                // Retroactive completions (a standing value already met) finish it at once.
                engine.checkCompletion(s, quest);
            }
            return Boolean.valueOf(accepted);
        }));
        if (!ok) {
            reopenSame(player, ref, store, liveSearch);
            return;
        }
        try {
            deps.actionFeedback().accepted(quest, store, ref, player);
        } catch (Throwable ignored) {
            // A consumer's feedback failing costs its own moment, never the page.
        }
        if (!deps.announcesActions()) {
            // A filled feedback seam owns the announcement; two toasts for one accept
            // would double-report the same moment.
            showToast(ToastKind.SUCCESS, text("book.toast.accepted"));
        }
        QuestStatus newState = engine.status(subject, quest);
        boolean atMax = engine.maxActive() > 0 && engine.logSlotsUsed(subject) >= engine.maxActive();
        if (newState == QuestStatus.COMPLETED || newState == QuestStatus.COMPLETED_UNCLAIMED || atMax) {
            // Completed immediately, or the cap state changed for every other row - reopen.
            reopenSame(player, ref, store, liveSearch);
        } else {
            BookQuestsTab.sendQuestAcceptedUpdate(this, data.selector, quest, subject, engine);
        }
    }

    private void handleAbandon(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                               @Nonnull Player player, @Nonnull ObjectiveBookEventData data,
                               @Nonnull String liveSearch) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = engine.quest(data.id);
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        if (quest == null || subject == null) {
            reopenSame(player, ref, store, liveSearch);
            return;
        }
        ProgressionCallScope scope = ProgressionRuntime.questScope();
        boolean ok = Boolean.TRUE.equals(scope.around(subject, s -> {
            boolean abandoned = engine.abandon(s, quest.id());
            engine.untrack(s, quest.id());
            return Boolean.valueOf(abandoned);
        }));
        if (ok) {
            try {
                deps.actionFeedback().abandoned(quest, store, ref, player);
            } catch (Throwable ignored) {
                // A consumer's feedback failing costs its own moment, never the page.
            }
            if (!deps.announcesActions()) {
                // Same stand-down as the accept path: the filled seam is the announcement.
                showToast(ToastKind.INFO, text("book.toast.abandoned"));
            }
        } else {
            showToast(ToastKind.WARNING, text("book.toast.abandon_failed"));
        }
        // A board-managed quest drops back to not-started, which the log must NOT render with an
        // Accept button (board-accepted only); a full repaint renders it through the corrected
        // branch instead of the pre-bound partial flashing Accept.
        if (deps.managedGuarded(quest)) {
            reopenSame(player, ref, store, liveSearch);
        } else {
            BookQuestsTab.sendQuestAbandonedUpdate(this, data.selector, subject, engine);
        }
    }

    private void handleToggleTrack(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                   @Nonnull ObjectiveBookEventData data) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = engine.quest(data.id);
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        UICommandBuilder cmd = new UICommandBuilder();
        if (quest != null && subject != null) {
            Message name = BookQuestsTab.nameOf(quest);
            boolean wasTracked = engine.tracked(subject).contains(quest.id());
            ProgressionCallScope scope = ProgressionRuntime.questScope();
            boolean nowTracked;
            if (wasTracked) {
                scope.around(subject, s -> Boolean.valueOf(engine.untrack(s, quest.id())));
                showToast(ToastKind.INFO, text("book.toast.untracked", name));
                nowTracked = false;
            } else {
                boolean ok = Boolean.TRUE.equals(scope.around(subject,
                        s -> Boolean.valueOf(engine.track(s, quest.id()))));
                if (ok) {
                    showToast(ToastKind.INFO, text("book.toast.tracked", name));
                } else {
                    showToast(ToastKind.ERROR, text("book.quests.track_cap", engine.maxTracked()));
                }
                nowTracked = ok;
            }
            // Tracking never moves a row, so no reopen: flip the pin glyph and repaint the
            // pre-allocated tracked side panel in place. The list keeps its scroll.
            if (data.selector != null) {
                paintPinIcon(cmd, data.selector + " #TrackBtn", nowTracked);
            }
            TrackedQuestPanelRenderer.render(cmd, null, subject, false, null);
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    private void handleTurnIn(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                              @Nonnull ObjectiveBookEventData data) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = engine.quest(data.id);
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        UICommandBuilder cmd = new UICommandBuilder();
        if (quest != null && subject != null && data.objectiveId != null) {
            ProgressionCallScope scope = ProgressionRuntime.questScope();
            Integer turnedIn = scope.around(subject,
                    s -> Integer.valueOf(engine.attemptTurnIn(s, quest, data.objectiveId)));
            int handed = turnedIn == null ? 0 : turnedIn.intValue();
            ObjectiveDef objective = quest.objective(data.objectiveId);
            Message itemName = objective == null || objective.target() == null
                    ? Msg.raw("") : NativeNames.itemNameMsg(objective.target());
            if (handed > 0) {
                int amount = objective == null ? 0 : objective.amountAsInt();
                // Counts are data; the item name is a NESTED client-resolved Message.
                showToast(ToastKind.SUCCESS, text("book.toast.turn_in", handed, amount, itemName));
                // Scroll-preserving in-place refresh for the new state (morph Hand in into a gold
                // Claim on completion, hide the row when it leaves the active bucket, else repaint
                // objective progress).
                if (data.selector != null) {
                    BookQuestsTab.refreshTurnedInRow(this, cmd, data.selector, quest, subject, engine);
                }
            } else {
                ObjectiveProgressState progress = engine.progressOf(subject, quest.id(), data.objectiveId);
                int current = progress != null ? progress.current() : 0;
                int required = objective == null ? 0 : objective.amountAsInt();
                showToast(ToastKind.ERROR,
                        text("book.quests.turn_in_fail", itemName, current, required));
            }
            BookQuestsTab.updateHeaderCounts(this, cmd, subject, engine);
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    // ==================== achievement actions ====================

    private void handleSelect(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                              @Nonnull ObjectiveBookEventData data) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        if (subject != null && data.id != null && !data.id.isBlank()) {
            // The id round-trips exactly as the row's achievement carried it; no re-casing here.
            String next = data.id.trim();
            Achievement achievement = engine.achievement(next);
            if (achievement != null) {
                // Swap the list highlight in place, then repaint ONLY the right panel: clear its
                // hosts, re-append, and bind the fresh chips in this update's own event builder.
                if (selectedRowSel != null) {
                    BookAchievementsTab.tintRowSelected(cmd, selectedRowSel, false);
                }
                if (data.selector != null) {
                    BookAchievementsTab.tintRowSelected(cmd, data.selector, true);
                }
                selectedRowSel = data.selector;
                selectedId = next;
                BookAchievementsTab.repaintDetailPartial(this, cmd, events, store, ref, subject,
                        engine, achievement);
            }
        }
        this.sendUpdate(cmd, events, false);
    }

    /**
     * The pin button is a TOGGLE dispatched on live state (like the quest track button), so a
     * scroll-preserving partial flip never leaves a stale verb baked into the binding.
     */
    private void handleTogglePin(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                 @Nonnull ObjectiveBookEventData data) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
        UICommandBuilder cmd = new UICommandBuilder();
        if (subject != null && data.id != null && !data.id.isBlank()) {
            Achievement achievement = engine.achievement(data.id);
            Message name = achievement != null
                    ? BookAchievementsTab.nameOf(achievement) : Msg.raw(data.id);
            try {
                ProgressionCallScope scope = ProgressionRuntime.achievementScope();
                boolean wasPinned = engine.pinned(subject).contains(data.id);
                boolean nowPinned;
                if (wasPinned) {
                    scope.around(subject, s -> Boolean.valueOf(engine.unpin(s, data.id)));
                    showToast(ToastKind.INFO, text("book.toast.unpinned", name));
                    nowPinned = false;
                } else {
                    nowPinned = Boolean.TRUE.equals(scope.around(subject,
                            s -> Boolean.valueOf(engine.pin(s, data.id))));
                    if (nowPinned) {
                        showToast(ToastKind.INFO, text("book.toast.pinned", name));
                    } else {
                        showToast(ToastKind.ERROR,
                                text("book.achievements.pin_cap", engine.maxPinned()));
                    }
                }
                if (data.selector != null) {
                    paintPinIcon(cmd, data.selector + " #PinBtn", nowPinned);
                }
            } catch (Throwable t) {
                SafeLog.warn("[progression] the book's pin toggle failed: " + t.getMessage());
            }
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    private void handleAchievementClaim(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        String id = selectedId;
        if (subject != null && id != null) {
            Achievement achievement = engine.achievement(id);
            if (achievement != null) {
                boolean ok = Boolean.TRUE.equals(ProgressionRuntime.achievementScope()
                        .around(subject, s -> Boolean.valueOf(engine.claim(s, achievement))));
                if (ok) {
                    showToast(ToastKind.REWARD, text("book.achievements.claim_success"));
                } else {
                    showToast(ToastKind.WARNING, text("book.toast.claim_failed"));
                }
                // Repaint the detail column so the reward tags and the claim button tell the new
                // truth; the list row's status has not changed (claiming never locks anything).
                BookAchievementsTab.repaintDetailPartial(this, cmd, events, store, ref, subject,
                        engine, achievement);
            }
        }
        this.sendUpdate(cmd, events, false);
    }

    private void handleMilestoneClaim(@Nonnull Ref<EntityStore> ref,
                                      @Nonnull Store<EntityStore> store, @Nonnull Player player,
                                      @Nonnull ObjectiveBookEventData data) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        int threshold = -1;
        try {
            threshold = Integer.parseInt(data.threshold);
        } catch (NumberFormatException ignored) {
            // A malformed threshold falls through to the no-op response below.
        }
        if (subject != null && threshold > 0) {
            ObjectiveBookDeps.MilestoneClaimOutcome outcome;
            try {
                outcome = deps.milestoneClaim().claim(threshold, store, ref, player);
            } catch (Throwable t) {
                SafeLog.warn("[progression] the book's milestone claim failed: " + t.getMessage());
                outcome = ObjectiveBookDeps.MilestoneClaimOutcome.NOT_READY;
            }
            switch (outcome) {
                case SUCCESS -> showToast(ToastKind.REWARD, text("book.achievements.claim_success"));
                case INVENTORY_FULL ->
                        showToast(ToastKind.ERROR, text("book.achievements.inventory_full"));
                case NOT_READY -> showToast(ToastKind.WARNING, text("book.toast.claim_failed"));
            }
            // Repaint the overview's milestone block + the header stat in place, so the claimed
            // card reads claimed without the list losing its scroll.
            BookAchievementsTab.repaintMilestonesPartial(this, cmd, events, store, ref, subject, engine);
        }
        this.sendUpdate(cmd, events, false);
    }
}
