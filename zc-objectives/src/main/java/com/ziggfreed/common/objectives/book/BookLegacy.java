package com.ziggfreed.common.objectives.book;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.StatusTones;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.rows.BuiltRows;

/**
 * What the pre-redesign tabs ({@code BookQuestsTab}, {@code BookAchievementsTab}) read off the page before the
 * shell replaced it: their templates, filter reads, binding state and row tints. Nothing constructs one and
 * nothing calls those tabs; this exists only so they keep compiling, unreferenced, until the phase-2 streams port
 * their assertions and delete them (W1-58 plan, sections 3.4 and 3.19). It goes with the last of them.
 */
final class BookLegacy {

    static final String QUEST_ROW_TEMPLATE = "Pages/ZigQuestLogRow.ui";
    static final String LINE_TEMPLATE = "Pages/ZigDetailLine.ui";
    static final String TAG_CHIP_TEMPLATE = "Pages/ZigBookTagChip.ui";
    static final String CAT_TAB_TEMPLATE = "Pages/ZigBookCatTab.ui";
    static final String WIDE_TAB_TEMPLATE = "Pages/ZigBookWideTab.ui";
    static final String ACH_ROW_TEMPLATE = "Pages/ZigAchListRow.ui";
    static final String ACH_CHIP_TEMPLATE = "Pages/ZigAchChipRow.ui";
    static final String ACH_CRITERION_TEMPLATE = "Pages/ZigAchCriterionRow.ui";
    static final String ACH_CATEGORY_CARD_TEMPLATE = "Pages/ZigAchCategoryCard.ui";
    static final String MILESTONE_TEMPLATE = "Pages/ZigMilestoneCard.ui";

    static final String QUEST_SEARCH = "#QSearch";
    static final String ACH_SEARCH = "#ASearch";
    static final String SEARCH_KEY = BookState.KEY_SEARCH_INPUT;
    static final int MAX_ROWS = 200;
    static final String ROW_SELECTED_TINT = "#1a2d44";
    static final String ROW_TINT = "#1a2233";
    static final String FILTER_ALL = BookState.ALL;

    /** The old strip widths (the right panel's padding, the side column, the two chip widths). */
    static final int RIGHT_PANEL_PADDING = 40;
    static final int SIDE_PANEL_WIDTH = 320;
    static final int CAT_TAB_OUTER_WIDTH = 102;
    static final int WIDE_TAB_OUTER_WIDTH = 166;

    @Nonnull private final BookState state;
    @Nonnull private final ObjectiveBookDeps deps;
    @Nonnull private final PlayerRef viewer;
    private final Set<String> expandedQuestIds = new HashSet<>();
    private final BuiltRows builtQuestRows = new BuiltRows();

    BookLegacy(@Nonnull BookState state, @Nonnull ObjectiveBookDeps deps, @Nonnull PlayerRef viewer) {
        this.state = state;
        this.deps = deps;
        this.viewer = viewer;
    }

    @Nonnull
    String filterCategory() {
        return state.category();
    }

    @Nonnull
    String filterStatus() {
        return state.status();
    }

    @Nonnull
    String searchText() {
        return state.search();
    }

    @Nonnull
    String filterTag() {
        return BookState.ALL.equals(state.tag()) ? "" : state.tag();
    }

    @Nonnull
    String filterSubcategory() {
        return "";
    }

    @Nonnull
    String sortMode() {
        return BookState.SORT_DEFAULT.equals(state.sort()) ? "" : state.sort();
    }

    @Nullable
    String selectedId() {
        return state.selectedId();
    }

    void rememberSelectedRow(@Nullable String rowSelector) {
        // The shell keeps the selection in its state; there is no row to remember here.
    }

    @Nonnull
    Set<String> expandedQuestIds() {
        return expandedQuestIds;
    }

    @Nonnull
    BuiltRows builtQuestRows() {
        return builtQuestRows;
    }

    @Nonnull
    ObjectiveBookDeps deps() {
        return deps;
    }

    @Nonnull
    PlayerRef viewer() {
        return viewer;
    }

    int stripWidthBudget() {
        return MenuFrame.BODY_WIDTH - RIGHT_PANEL_PADDING - SIDE_PANEL_WIDTH;
    }

    static boolean categoryChipsFit(int categoryCount, int chipOuterWidth, int widthBudget) {
        return (categoryCount + 1) * chipOuterWidth <= widthBudget;
    }

    @Nonnull
    Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr("ziggfreedcommon.", "progression." + key, args);
    }

    @Nonnull
    EventData fullState(@Nonnull String action) {
        return ZigSearchRow.carry(state.event(action), SEARCH_KEY, activeSearchRow());
    }

    @Nonnull
    String activeSearchRow() {
        return ObjectiveBookPage.TAB_ACHIEVEMENTS.equals(state.tab()) ? ACH_SEARCH : QUEST_SEARCH;
    }

    static void styleChipActive(@Nonnull UICommandBuilder cmd, @Nonnull String btnSelector, boolean active) {
        ZigRichButton.color(cmd, btnSelector, active ? StatusTones.IN_PROGRESS.hex() : null);
        cmd.set(btnSelector + " #Label.Style.RenderBold", active);
    }

    static void paintPinIcon(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector, boolean on) {
        cmd.set(buttonSelector + " #PinIconOff.Visible", !on);
        cmd.set(buttonSelector + " #PinIconOn.Visible", on);
        cmd.set(buttonSelector + ".Style.Default.Background", on ? "#3a2d10" : "#1a2233");
        cmd.set(buttonSelector + ".Style.Hovered.Background", on ? "#4a3a18" : "#243144");
        cmd.set(buttonSelector + ".Style.Pressed.Background", on ? "#2a1d08" : "#0f1723");
    }

    static void applyGoldClaim(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector) {
        UiRetint.retintButtonStates(cmd, buttonSelector, "#6b5a2e", "#8a7440", "#57481f");
        ZigRichButton.color(cmd, buttonSelector, "#ffcc4a");
    }
}
