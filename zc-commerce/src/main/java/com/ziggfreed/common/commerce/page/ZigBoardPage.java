package com.ziggfreed.common.commerce.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.board.event.BoardEvents;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.BountyAssetRef;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A board of contracts: what is up right now on the left, the one being read on the right, and the
 * clock to the next turnover across the top.
 *
 * <p>This is the GENERIC screen, driven entirely through the board engine and the shared quest
 * runtime, so it renders whatever boards a server has whoever shipped them. A consumer contributes
 * what the library cannot know through {@link CommercePageDeps} - a theme, what its wallets are
 * called, what follows a settled contract - and never a page of its own.
 *
 * <h2>A bounty is a quest, and nothing here forgets it</h2>
 *
 * <p>Every lifecycle call goes to the quest engine: accept threads the BOARD as the site it was
 * taken at, a hand-in and a claim name that same board, and the engine's own predicates decide
 * whether either is allowed. So a contract taken here appears in the objective book like any other
 * quest, and a reward earned here cannot be collected at a board it was not taken from.
 *
 * <h2>Two lists, one board</h2>
 *
 * <p>The BOARD tab is what is posted right now, the player's own re-rolled positions laid over the
 * shared draw. The MINE tab is everything they are carrying that came off this board, wherever they
 * happen to be with it - which is what keeps a finished contract reachable after the rotation that
 * offered it has turned over.
 *
 * <h2>Nothing is charged for something that could not have happened</h2>
 *
 * <p>The lapse re-arm and the pre-charge reroll probe are ENGINE behaviour this page merely calls, so
 * no screen can reintroduce the charge-for-a-no-op bug. A paid reroll additionally sits behind two
 * clicks: the first says the price, the second spends it.
 *
 * <p>EVERY exit path sends a response - a reopen, a partial update, or a close - or the client spins
 * forever.
 */
public final class ZigBoardPage extends ToastablePage<BoardEventData> {

    /** What the board is posting right now; also the default. */
    public static final String TAB_BOARD = "board";

    /** What the player is carrying off this board, wherever they are with it. */
    public static final String TAB_MINE = "mine";

    private static final String PAGE_TEMPLATE = "Pages/ZigBoardPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigCommerceRow.ui";

    /** What a theme is offered to repaint: the panel carrying the list. */
    private static final String FRAME_SELECTOR = "#LeftPanel";

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.commerce.lang} file). */
    private static final String DOMAIN = "commerce.";

    private static final int MAX_ROWS = 200;
    private static final int MAX_LINES = 24;
    private static final int MAX_CHIPS = 6;

    /** The marker {@link #builtRowOrder} carries where a section heading was drawn. */
    private static final String HEADER_ROW = "";

    private static final String ROW_SELECTED_TINT = "#1a2d44";
    private static final String ROW_SELECTED_TEXT = "#ffffff";
    private static final String ROW_TINT = "#41506a";
    private static final String ROW_HOVER_TINT = "#5b6f8c";
    private static final String ROW_PRESSED_TINT = "#344156";
    private static final String ROW_TEXT = "#b6c9de";

    /**
     * A section heading is a HEADING: it reads at least as brightly as the rows under it, or a
     * player takes "Available" and "Locked" for greyed-out entries rather than for the two runs they
     * divide. Kept in step with the row template's own default for the same element.
     */
    private static final String HEADER_TEXT = "#c2d4e8";

    @Nonnull private final String boardId;

    @Nonnull private final CommercePageDeps deps;

    /** The contract this page was opened AT, preselected for as long as the page lives. */
    @Nullable private final String openedAtBountyId;

    @Nullable private String selectedBountyId;

    @Nonnull private String activeTab = TAB_BOARD;

    /** Two clicks before a reroll charges anything. Page-instance state, never persisted. */
    private final ConfirmArm rerollArm = new ConfirmArm();

    /**
     * The exact row order the last full build rendered, section headings included as
     * {@link #HEADER_ROW} markers so a contract's index is the one the client DOM actually holds.
     */
    private final List<String> builtRowOrder = new ArrayList<>();

    /** Which position on the board each drawn contract came from, for a reroll press. */
    private final Map<String, Integer> positionOf = new LinkedHashMap<>();

    public ZigBoardPage(@Nonnull PlayerRef playerRef, @Nonnull String boardId,
            @Nullable String openAtBountyId, @Nonnull CommercePageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, BoardEventData.CODEC);
        this.boardId = boardId;
        this.openedAtBountyId = trimToNull(openAtBountyId);
        this.selectedBountyId = this.openedAtBountyId;
        this.deps = deps;
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));

        builtRowOrder.clear();
        positionOf.clear();

        BoardAssetSpec board = CommerceCatalogs.boards().board(boardId);
        cmd.set("#BoardTitle.TextSpans", CommerceText.title(board == null ? null : board.asset().getText(),
                deps.titleArgs(), text("board.title")));
        Message flavor = board == null ? null
                : CommerceText.flavor(board.asset().getText(), deps.titleArgs());
        if (flavor != null) {
            cmd.set("#BoardSubtitle.TextSpans", flavor);
            cmd.set("#BoardSubtitle.Visible", true);
        }
        setIcon(cmd, "#BoardIconSlot", "#BoardIcon", board == null ? null : board.asset().getIcon());
        paintTabs(cmd, events);

        Subject subject = subjectOf(store, ref);
        if (board == null || subject == null) {
            showEmpty(cmd, text(board == null ? "board.empty.unknown" : "board.empty.unavailable"));
            renderToastInto(cmd);
            return;
        }
        if (!board.enabled()) {
            showEmpty(cmd, text("board.empty.closed"));
            renderToastInto(cmd);
            return;
        }

        long now = System.currentTimeMillis();
        cmd.set("#RefreshLabel.TextSpans", text("board.refresh_in",
                CommerceText.countdownMessage(board.rotation().millisUntilNext(now))));
        CommerceChips.render(cmd, "#BalanceRow",
                CommerceChips.balances(CommerceDefaults.currencyEngine(), subject, board.currencies(),
                        deps.currencyNames()),
                MAX_CHIPS);
        // A board is a pure function of the clock with nothing scheduled anywhere, so a turnover is
        // noticed the first time somebody looks after it happened. The event fires once per period.
        BoardEvents.noticeRotation(board.boardId(), board.rotation().periodIndex(now), now);

        BoardEngine engine = CommerceEngines.boards();
        QuestEngine quests = questEngine();
        if (quests == null) {
            showEmpty(cmd, text("board.empty.unavailable"));
            renderToastInto(cmd);
            return;
        }
        // The lapse re-arm is what makes a rotation genuinely rotate: a contract completed in a PAST
        // period is put back within reach the moment the board is looked at, with nothing scheduled.
        reArm(engine, subject, board, now);

        List<BountyRef> shown = TAB_MINE.equals(activeTab)
                ? carriedFrom(engine, quests, subject, board, now)
                : engine.activeSetFor(subject, board, CommerceCatalogs.boards().pool(), now);
        for (int position = 0; position < shown.size(); position++) {
            positionOf.putIfAbsent(CommerceText.normalize(shown.get(position).bountyId()),
                    Integer.valueOf(position));
        }

        Map<String, BountyRef> byId = new LinkedHashMap<>();
        List<BoardSections.Entry> entries = new ArrayList<>();
        for (BountyRef ref2 : shown) {
            Quest quest = quests.quest(ref2.bountyId());
            if (quest == null) {
                // A contract the quest runtime never received cannot be taken, so drawing it would
                // be a row that does nothing. The publish step says so in the boot log.
                continue;
            }
            byId.put(CommerceText.normalize(ref2.bountyId()), ref2);
            entries.add(BoardSections.Entry.of(ref2.bountyId(),
                    sectionOf(engine, quests, subject, board, ref2, quest, now)));
        }
        List<BoardSections.Entry> ordered = BoardSections.sort(entries);
        cmd.set("#BountyCount.TextSpans", text("board.count", ordered.size()));

        this.selectedBountyId = BoardSections.select(BoardSections.sortedIds(ordered),
                openedAtBountyId, selectedBountyId);

        if (ordered.isEmpty()) {
            showEmptyList(cmd);
            renderToastInto(cmd);
            return;
        }
        appendRows(cmd, events, engine, quests, subject, board, ordered, byId, now);

        // Bound ONCE per build with no contract id: the handlers act on whatever the detail panel is
        // showing, so a partial update can swap the panel without needing a binding it cannot add.
        bindAction(events, "#AcceptBtn", "accept");
        bindAction(events, "#TurnInBtn", "turnIn");
        bindAction(events, "#ClaimBtn", "claim");
        bindAction(events, "#AbandonBtn", "abandon");
        bindAction(events, "#RerollBtn", "reroll");

        BountyRef selected = selectedBountyId == null ? null
                : byId.get(CommerceText.normalize(selectedBountyId));
        Quest selectedQuest = selected == null ? null : quests.quest(selected.bountyId());
        if (selected != null && selectedQuest != null) {
            renderDetail(cmd, engine, quests, subject, board, selected, selectedQuest, now);
        } else {
            cmd.set("#RightPanel.Visible", false);
        }
        renderToastInto(cmd);
    }

    /**
     * Get the page's markup onto the screen, through a consumer's theme where there is one. Guarded,
     * with the plain append as the fallback: a decoration that throws must not cost the whole screen,
     * and a theme that threw AFTER appending would append twice, so the retry only runs when nothing
     * landed.
     */
    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, FRAME_SELECTOR);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[commerce] a page theme failed, so the board renders plain: " + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    /**
     * The subject the quest engine and the commerce store both understand, from the shared runtime.
     * One built here would read neutral through whichever stores are installed and silently drop
     * every write, so an accept would report success and change nothing.
     */
    @Nullable
    private Subject subjectOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            // The build argument is the page's ANCHOR, which at a character is that character's own
            // entity, so the player is resolved from the reference this page was built with.
            return ProgressionRuntime.subjects().questSubject(store, playerEntityRef(ref));
        } catch (Throwable t) {
            SafeLog.warn("[commerce] no subject could be built for this player: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private static QuestEngine questEngine() {
        try {
            return ProgressionRuntime.quests();
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the quest runtime is not available, so the board has no "
                    + "contracts to show: " + t.getMessage());
            return null;
        }
    }

    private void reArm(@Nonnull BoardEngine engine, @Nonnull Subject subject,
            @Nonnull BoardAssetSpec board, long now) {
        try {
            engine.reArmLapsed(subject, board, CommerceCatalogs.boards().pool(), now);
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the board's lapse re-arm failed", t);
        }
    }

    /**
     * What the player is carrying that came off THIS board: read off the accept site the quest engine
     * recorded, so a contract stays this board's business while it is being carried even after the
     * rotation that offered it has turned over.
     */
    @Nonnull
    private List<BountyRef> carriedFrom(@Nonnull BoardEngine engine, @Nonnull QuestEngine quests,
            @Nonnull Subject subject, @Nonnull BoardAssetSpec board, long now) {
        Set<String> carried = new LinkedHashSet<>();
        for (Quest quest : quests.activeAndUnclaimed(subject)) {
            String site = quests.acceptSiteOf(subject, quest.id());
            if (CommerceText.sameId(site, board.boardId())) {
                carried.add(CommerceText.normalize(quest.id()));
            }
        }
        List<BountyRef> out = new ArrayList<>();
        for (BountyRef ref : engine.membersOf(board, CommerceCatalogs.boards().pool())) {
            if (carried.contains(CommerceText.normalize(ref.bountyId()))) {
                out.add(ref);
            }
        }
        return out;
    }

    @Nonnull
    private BoardSections.Section sectionOf(@Nonnull BoardEngine engine, @Nonnull QuestEngine quests,
            @Nonnull Subject subject, @Nonnull BoardAssetSpec board, @Nonnull BountyRef ref,
            @Nonnull Quest quest, long now) {
        QuestStatus status = quests.status(subject, quest);
        boolean acceptable = engine.canAccept(subject, board, ref, now).ok();
        boolean readyHere = quests.readyToTurnInAt(subject, quest, board.boardId());
        boolean collectHere = quests.canCompleteAt(subject, quest, board.boardId());
        boolean spent = engine.completedThisPeriod(subject, board, ref.bountyId(), now);
        return BoardSections.classify(status, acceptable, readyHere, collectHere, spent);
    }

    // ==================== the list ====================

    private void appendRows(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull BoardEngine engine, @Nonnull QuestEngine quests, @Nonnull Subject subject,
            @Nonnull BoardAssetSpec board, @Nonnull List<BoardSections.Entry> ordered,
            @Nonnull Map<String, BountyRef> byId, long now) {
        BoardSections.Section open = null;
        int index = 0;
        for (BoardSections.Entry entry : ordered) {
            BountyRef ref = byId.get(CommerceText.normalize(entry.bountyId()));
            if (ref == null) {
                continue;
            }
            boolean opensSection = entry.section() != open;
            // A heading and the row it heads are budgeted TOGETHER, so a list cut short by the row
            // ceiling never ends on a heading with nothing under it.
            if (index + (opensSection ? 1 : 0) >= MAX_ROWS) {
                break;
            }
            if (opensSection) {
                open = entry.section();
                index = appendHeader(cmd, index, sectionText(entry.section()));
            }
            index = appendBountyRow(cmd, events, index, entry, ref, board);
        }
    }

    private int appendHeader(@Nonnull UICommandBuilder cmd, int index, @Nonnull Message label) {
        String sel = appendRow(cmd, index);
        builtRowOrder.add(HEADER_ROW);
        cmd.set(sel + " #RowBtn.Visible", false);
        cmd.set(sel + " #StatusDot.Visible", false);
        cmd.set(sel + " #SectionLabel.TextSpans", label);
        cmd.set(sel + " #SectionLabel.Style.TextColor", HEADER_TEXT);
        cmd.set(sel + " #SectionLabel.Visible", true);
        return index + 1;
    }

    private int appendBountyRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            int index, @Nonnull BoardSections.Entry entry, @Nonnull BountyRef ref,
            @Nonnull BoardAssetSpec board) {
        String sel = appendRow(cmd, index);
        builtRowOrder.add(ref.bountyId());
        ZigRichButton.text(cmd, sel + " #RowBtn", bountyName(ref));
        cmd.set(sel + " #StatusDot.Background", dotColor(entry.section()));
        Message grade = gradeLabel(ref, board);
        if (grade != null) {
            cmd.set(sel + " #RowBadge.TextSpans", grade);
            cmd.set(sel + " #RowBadge.Visible", true);
        }
        if (CommerceText.sameId(ref.bountyId(), selectedBountyId)) {
            paintRowSelected(cmd, sel, true);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RowBtn",
                EventData.of("Action", "select").append("BountyId", ref.bountyId()), false);
        return index + 1;
    }

    @Nonnull
    private static String appendRow(@Nonnull UICommandBuilder cmd, int index) {
        cmd.append("#BountyList", ROW_TEMPLATE);
        return "#BountyList[" + index + "]";
    }

    private static void paintRowSelected(@Nonnull UICommandBuilder cmd, @Nonnull String rowSel,
            boolean selected) {
        if (selected) {
            UiRetint.retintButtonStates(cmd, rowSel + " #RowBtn",
                    ROW_SELECTED_TINT, ROW_SELECTED_TINT, ROW_SELECTED_TINT);
            ZigRichButton.color(cmd, rowSel + " #RowBtn", ROW_SELECTED_TEXT);
            cmd.set(rowSel + " #RowBtn #Label.Style.RenderBold", true);
        } else {
            UiRetint.retintButtonStates(cmd, rowSel + " #RowBtn",
                    ROW_TINT, ROW_HOVER_TINT, ROW_PRESSED_TINT);
            ZigRichButton.color(cmd, rowSel + " #RowBtn", ROW_TEXT);
            cmd.set(rowSel + " #RowBtn #Label.Style.RenderBold", false);
        }
    }

    /**
     * The empty-state line, with the tab bar left visible and bound: a whole-page empty state would
     * hide the only route back to the other list.
     */
    private void showEmptyList(@Nonnull UICommandBuilder cmd) {
        showEmpty(cmd, text(TAB_MINE.equals(activeTab) ? "board.empty.mine" : "board.empty.posted"));
    }

    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull Message line) {
        cmd.set("#EmptyLabel.TextSpans", line);
        cmd.set("#EmptyLabel.Visible", true);
        cmd.set("#RightPanel.Visible", false);
    }

    private void paintTabs(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        boolean mine = TAB_MINE.equals(activeTab);
        ZigRichButton.text(cmd, "#TabBoard", text("board.tab.posted"));
        ZigRichButton.text(cmd, "#TabMine", text("board.tab.mine"));
        bindTab(events, "#TabBoard", TAB_BOARD);
        bindTab(events, "#TabMine", TAB_MINE);
        styleTab(cmd, "#TabBoard", !mine);
        styleTab(cmd, "#TabMine", mine);
    }

    private static void bindTab(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String target) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", "tab").append("Tab", target), false);
    }

    private static void styleTab(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            boolean active) {
        String base = active ? "#5e86bd" : "#2f3b49";
        UiRetint.retintButtonStates(cmd, selector, base, active ? "#6f97cf" : "#445364", base);
        ZigRichButton.color(cmd, selector, active ? "#ffffff" : "#9fb0c2");
    }

    // ==================== the detail panel ====================

    /**
     * Paint the right-hand panel for one contract. IDEMPOTENT, so a partial update can re-render it
     * in place: every appended container is cleared first and EVERY conditional control's visibility
     * is set explicitly, so nothing from the previous contract survives.
     */
    private void renderDetail(@Nonnull UICommandBuilder cmd, @Nonnull BoardEngine engine,
            @Nonnull QuestEngine quests, @Nonnull Subject subject, @Nonnull BoardAssetSpec board,
            @Nonnull BountyRef ref, @Nonnull Quest quest, long now) {
        cmd.clear("#ObjectivesList");
        cmd.clear("#RewardsList");
        cmd.clear("#StatusList");
        cmd.set("#RightPanel.Visible", true);
        cmd.set("#Flavor.Visible", false);
        cmd.set("#DetailGrade.Visible", false);
        cmd.set("#RewardsHeader.Visible", false);
        cmd.set("#AcceptBtn.Visible", false);
        cmd.set("#TurnInBtn.Visible", false);
        cmd.set("#ClaimBtn.Visible", false);
        cmd.set("#AbandonBtn.Visible", false);
        cmd.set("#RerollBtn.Visible", false);
        cmd.set("#RerollCostRow.Visible", false);

        BoardSections.Section section = sectionOf(engine, quests, subject, board, ref, quest, now);
        cmd.set("#DetailTitle.TextSpans", bountyName(ref));
        cmd.set("#DetailStatus.TextSpans", sectionText(section));
        cmd.set("#DetailStatus.Style.TextColor", dotColor(section));
        Message grade = gradeLabel(ref, board);
        if (grade != null) {
            cmd.set("#DetailGrade.TextSpans", grade);
            cmd.set("#DetailGrade.Visible", true);
        }
        Message flavor = CommerceText.flavor(textOf(ref), deps.titleArgs());
        if (flavor != null) {
            cmd.set("#Flavor.TextSpans", flavor);
            cmd.set("#Flavor.Visible", true);
        }

        renderObjectives(cmd, quests, subject, ref, quest);
        renderRewards(cmd, quest);

        int statusLines = 0;
        switch (section) {
            case AVAILABLE -> {
                ZigRichButton.text(cmd, "#AcceptBtn", text("board.action.accept"));
                cmd.set("#AcceptBtn.Visible", true);
            }
            case LOCKED -> statusLines = renderRefusals(cmd,
                    engine.canAccept(subject, board, ref, now).reason(), statusLines);
            case SPENT -> statusLines = renderLine(cmd, text("board.status.spent_detail"), statusLines);
            case READY -> {
                ZigRichButton.text(cmd, "#ClaimBtn", text("board.action.claim"));
                cmd.set("#ClaimBtn.Visible", true);
            }
            case TURN_IN -> {
                ObjectiveDef step = quests.firstActiveTurnIn(subject, quest, board.boardId());
                if (step != null) {
                    ZigRichButton.text(cmd, "#TurnInBtn", text(step.target().isEmpty()
                            ? "board.action.report" : "board.action.turn_in"));
                    cmd.set("#TurnInBtn.Visible", true);
                }
                ZigRichButton.text(cmd, "#AbandonBtn", text("board.action.abandon"));
                cmd.set("#AbandonBtn.Visible", true);
            }
            case ACTIVE -> {
                ZigRichButton.text(cmd, "#AbandonBtn", text("board.action.abandon"));
                cmd.set("#AbandonBtn.Visible", true);
                if (quests.status(subject, quest) == QuestStatus.COMPLETED_UNCLAIMED) {
                    // Finished, and its reward belongs at the board it was taken from. The status
                    // line has already said so; offering a button that would refuse says it worse.
                    statusLines = renderLine(cmd, text("board.status.parked"), statusLines);
                }
            }
            case DONE -> {
                // Finished and collected: there is nothing left to press.
            }
        }
        renderReroll(cmd, engine, subject, board, ref, section, now, statusLines);
    }

    /**
     * Every step, with its count where the player is carrying the contract and without one where they
     * are not - a contract being read BEFORE it is taken shows what it asks for, not a wall of
     * zeroes.
     */
    private void renderObjectives(@Nonnull UICommandBuilder cmd, @Nonnull QuestEngine quests,
            @Nonnull Subject subject, @Nonnull BountyRef ref, @Nonnull Quest quest) {
        cmd.set("#ObjectivesHeader.TextSpans", text("board.header.objectives"));
        QuestStatus status = quests.status(subject, quest);
        boolean carried = status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED;
        Map<String, ObjectiveProgressState> progress = carried
                ? quests.progressOf(subject, quest.id()) : Map.of();
        List<ObjectiveDef> objectives = quest.objectives();
        int shown = Math.min(objectives.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            ObjectiveDef objective = objectives.get(i);
            String sel = CommerceChips.appendLine(cmd, "#ObjectivesList", i);
            Message name = objectiveName(ref, quest, objective);
            if (!carried) {
                CommerceChips.setLine(cmd, sel, name, "#8fa6bd", null);
                continue;
            }
            ObjectiveProgressState state = progress.get(objective.id());
            int current = state != null ? state.current() : 0;
            int required = state != null ? state.required() : objective.amountAsInt();
            boolean done = state != null && state.isCompleted();
            CommerceChips.setLine(cmd, sel,
                    Msg.join(name, Msg.raw("  "), text("board.progress", current, required)),
                    done ? CommerceChips.COLOR_DONE : CommerceChips.COLOR_LINE, null);
        }
    }

    private void renderRewards(@Nonnull UICommandBuilder cmd, @Nonnull Quest quest) {
        List<RewardChip> chips = RewardChips.chipsFor(quest.rewards(), deps.rewardChips());
        if (chips.isEmpty()) {
            return;
        }
        cmd.set("#RewardsHeader.TextSpans", text("board.header.rewards"));
        cmd.set("#RewardsHeader.Visible", true);
        int shown = Math.min(chips.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            RewardChip chip = chips.get(i);
            CommerceChips.setLine(cmd, CommerceChips.appendLine(cmd, "#RewardsList", i), chip.label(),
                    CommerceChips.COLOR_LINE, chip.iconItemId());
        }
    }

    /**
     * Why a visible contract cannot be taken, so a locked row explains itself instead of sitting
     * inert. A gate refusal reads as the generic locked line rather than leaking whatever an author
     * gated on, which is the same rule the objective book and the NPC quest page follow.
     */
    private int renderRefusals(@Nonnull UICommandBuilder cmd, @Nullable String reason, int index) {
        return renderLine(cmd, text(CommerceRefusals.keyOf(reason)), index);
    }

    private int renderLine(@Nonnull UICommandBuilder cmd, @Nonnull Message line, int index) {
        if (index >= MAX_LINES) {
            return index;
        }
        CommerceChips.setLine(cmd, CommerceChips.appendLine(cmd, "#StatusList", index), line,
                CommerceChips.COLOR_REFUSAL, null);
        return index + 1;
    }

    /**
     * The reroll footer, offered only for a contract that is still on the board and not yet taken -
     * an accepted one cannot be rerolled out from under the player. When the period's rerolls are
     * spent the button stays visible and LOCKED with the reason beside it, so the limit is legible
     * rather than the control simply vanishing.
     */
    private void renderReroll(@Nonnull UICommandBuilder cmd, @Nonnull BoardEngine engine,
            @Nonnull Subject subject, @Nonnull BoardAssetSpec board, @Nonnull BountyRef ref,
            @Nonnull BoardSections.Section section, long now, int statusIndex) {
        RerollSpec spec = board.reroll();
        boolean stillOnTheBoard = section == BoardSections.Section.AVAILABLE
                || section == BoardSections.Section.LOCKED;
        if (spec == null || !stillOnTheBoard) {
            // A contract already taken cannot be rerolled out from under the player, and one being
            // carried is not a slot on the board any more.
            return;
        }
        Integer position = positionOf.get(CommerceText.normalize(ref.bountyId()));
        if (position == null) {
            return;
        }
        cmd.set("#RerollBtn.Visible", true);
        boolean armed = rerollArm.isArmed(armKey(ref.bountyId()), now);
        ZigRichButton.text(cmd, "#RerollBtn", text(armed ? "action.confirm" : "board.action.reroll"));
        ZigRichButton.color(cmd, "#RerollBtn", armed ? CommerceChips.COLOR_SHORT : ROW_TEXT);

        BoardEngine.BoardCheck check = engine.canReroll(subject, board,
                CommerceCatalogs.boards().pool(), position.intValue(), now);
        if (!check.ok()) {
            paintLocked(cmd, "#RerollBtn");
            renderLine(cmd, text(CommerceRefusals.keyOf(check.reason())), statusIndex);
            return;
        }
        if (!spec.cost().isFree()) {
            cmd.set("#RerollCostRow.Visible", true);
            CommerceChips.render(cmd, "#RerollCostRow", CommerceChips.price(spec.cost(),
                    CommerceDefaults.currencyEngine(), subject, deps.currencyNames()), MAX_CHIPS);
        }
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull BoardEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action;
        if (action == null || "close".equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if ("tab".equals(action)) {
            this.activeTab = TAB_MINE.equals(data.tab) ? TAB_MINE : TAB_BOARD;
            // The new list auto-selects its own first row.
            this.selectedBountyId = null;
            this.rerollArm.reset();
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if ("select".equals(action)) {
            selectBounty(ref, store, player, data.bountyId);
            return;
        }

        Subject subject = subjectOf(store, ref);
        QuestEngine quests = questEngine();
        BoardAssetSpec board = CommerceCatalogs.boards().board(boardId);
        BountyAssetRef bounty = selectedBountyId == null ? null
                : CommerceCatalogs.boards().bounty(selectedBountyId);
        Quest quest = quests == null || bounty == null ? null : quests.quest(bounty.bountyId());
        if (subject == null || quests == null || board == null || bounty == null || quest == null) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        BoardEngine engine = CommerceEngines.boards();
        if ("turnIn".equals(action)) {
            // The hand-in owns its own response, because a settled contract may hand the screen over
            // to whatever comes next instead of returning to this page.
            doTurnIn(ref, store, player, engine, quests, subject, board, quest);
            return;
        }
        if ("accept".equals(action)) {
            doAccept(engine, subject, board, bounty);
        } else if ("claim".equals(action)) {
            doClaim(quests, subject, board, quest);
        } else if ("abandon".equals(action)) {
            doAbandon(quests, subject, quest);
        } else if ("reroll".equals(action)) {
            doReroll(engine, subject, board, bounty);
        }
        player.getPageManager().openCustomPage(ref, store, this);
    }

    /**
     * Swap the highlighted row and re-render the detail panel in place, so the list keeps its scroll
     * position. Falls back to a full reopen when the clicked row is not one the last build recorded,
     * because a recomputed index can address a different row entirely - a section heading has no
     * status dot, and an unresolved selector disconnects the player.
     */
    private void selectBounty(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String bountyId) {
        // An arm left standing behind a different row would charge for a click that was never about
        // it, so changing what the panel shows forgets every arm.
        rerollArm.reset();
        Subject subject = subjectOf(store, ref);
        QuestEngine quests = questEngine();
        BoardAssetSpec board = CommerceCatalogs.boards().board(boardId);
        BountyAssetRef bounty = bountyId == null ? null
                : CommerceCatalogs.boards().bounty(bountyId);
        Quest quest = quests == null || bounty == null ? null : quests.quest(bounty.bountyId());
        int row = bountyId == null ? -1 : indexOfRow(bountyId);
        if (subject == null || quests == null || board == null || bounty == null || quest == null
                || row < 0) {
            this.selectedBountyId = bountyId;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        String previous = this.selectedBountyId;
        this.selectedBountyId = bounty.bountyId();
        UICommandBuilder cmd = new UICommandBuilder();
        int oldRow = previous == null ? -1 : indexOfRow(previous);
        if (oldRow >= 0 && oldRow != row) {
            paintRowSelected(cmd, "#BountyList[" + oldRow + "]", false);
        }
        paintRowSelected(cmd, "#BountyList[" + row + "]", true);
        renderDetail(cmd, CommerceEngines.boards(), quests, subject, board, bounty, quest,
                System.currentTimeMillis());
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    private int indexOfRow(@Nonnull String bountyId) {
        for (int i = 0; i < builtRowOrder.size(); i++) {
            if (CommerceText.sameId(builtRowOrder.get(i), bountyId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Take the contract on, RECORDING that it was taken at this board.
     *
     * <p>The site is what binds the hand-in back here and what keeps the contract on this board's own
     * list while it is being carried. The engine threads it on every accept, so no bounty author
     * writes a word of it.
     */
    private void doAccept(@Nonnull BoardEngine engine, @Nonnull Subject subject,
            @Nonnull BoardAssetSpec board, @Nonnull BountyRef bounty) {
        BoardEngine.BoardCheck result =
                scoped(subject, s -> engine.accept(s, board, bounty, System.currentTimeMillis()));
        if (result != null && result.ok()) {
            this.selectedBountyId = bounty.bountyId();
            showToast(ToastKind.SUCCESS, text("board.toast.accepted"));
            return;
        }
        showToast(ToastKind.ERROR,
                text(CommerceRefusals.keyOf(result == null ? null : result.reason())));
    }

    /** Collect a finished contract AT this board, which is where the engine will allow it. */
    private void doClaim(@Nonnull QuestEngine quests, @Nonnull Subject subject,
            @Nonnull BoardAssetSpec board, @Nonnull Quest quest) {
        Boolean ok = scoped(subject, s -> Boolean.valueOf(quests.claim(s, quest, board.boardId())));
        if (Boolean.TRUE.equals(ok)) {
            showToast(completionToast(quest));
            return;
        }
        showToast(ToastKind.WARNING, text("board.toast.claim_failed"));
    }

    private void doAbandon(@Nonnull QuestEngine quests, @Nonnull Subject subject,
            @Nonnull Quest quest) {
        Boolean ok = scoped(subject, s -> Boolean.valueOf(quests.abandon(s, quest.id())));
        if (Boolean.TRUE.equals(ok)) {
            showToast(ToastKind.INFO, text("board.toast.abandoned"));
        }
    }

    /**
     * Hand in the outstanding step at this board, and answer the player one way or another on every
     * path.
     *
     * <p>The button is offered whenever a step is outstanding rather than only when the player is
     * carrying everything, so a shortfall is answered with what is still owed instead of a control
     * that is silently not there.
     */
    private void doTurnIn(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nonnull BoardEngine engine, @Nonnull QuestEngine quests,
            @Nonnull Subject subject, @Nonnull BoardAssetSpec board, @Nonnull Quest quest) {
        ObjectiveDef step = quests.firstActiveTurnIn(subject, quest, board.boardId());
        if (step == null) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        Integer handed = scoped(subject, s ->
                Integer.valueOf(quests.attemptTurnIn(s, quest, step.id(), board.boardId())));
        if (handed == null || handed.intValue() <= 0) {
            ObjectiveProgressState state = quests.progressOf(subject, quest.id(), step.id());
            showToast(ToastKind.WARNING, state == null
                    ? text("board.toast.turn_in_failed")
                    : text("board.toast.turn_in_short", state.current(), state.required()));
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if (quests.status(subject, quest) == QuestStatus.ACTIVE) {
            showToast(ToastKind.SUCCESS, text("board.toast.turned_in"));
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        // ORDER IS LOAD-BEARING: the toast goes up FIRST, because whatever the hand-off opens
        // repaints the shared per-player toast state, so showing it afterwards would post it to a
        // screen that has already gone.
        showToast(completionToast(quest));
        if (!handOff(quest, store, ref, player)) {
            player.getPageManager().openCustomPage(ref, store, this);
        }
    }

    /**
     * Swap the shown contract for another from its slot, behind two clicks when it costs anything.
     * The engine probes for an alternative before draining, so a press that could change nothing
     * refuses instead of charging.
     */
    private void doReroll(@Nonnull BoardEngine engine, @Nonnull Subject subject,
            @Nonnull BoardAssetSpec board, @Nonnull BountyRef bounty) {
        RerollSpec spec = board.reroll();
        Integer position = positionOf.get(CommerceText.normalize(bounty.bountyId()));
        if (spec == null || position == null) {
            return;
        }
        long now = System.currentTimeMillis();
        BoardEngine.BoardCheck probe = engine.canReroll(subject, board,
                CommerceCatalogs.boards().pool(), position.intValue(), now);
        if (!probe.ok()) {
            showToast(ToastKind.ERROR, text(CommerceRefusals.keyOf(probe.reason())));
            return;
        }
        Cost price = spec.cost();
        if (!price.isFree() && !rerollArm.confirm(armKey(bounty.bountyId()), now)) {
            showToast(ToastKind.WARNING, text("board.toast.reroll_confirm", priceLine(price)));
            return;
        }
        BoardEngine.RerollResult result = engine.reroll(subject, board,
                CommerceCatalogs.boards().pool(), position.intValue(), now);
        if (!result.ok()) {
            showToast(ToastKind.ERROR, text(CommerceRefusals.keyOf(result.reason())));
            return;
        }
        BoardEvents.fireRerolled(board.boardId(), playerRef.getUuid(), result.position(),
                result.replacedId(), result.newId() == null ? "" : result.newId());
        // Keep the player looking at the slot they just paid to change.
        this.selectedBountyId = result.newId();
    }

    /**
     * Run a mutating call inside the registered progression scope, which is what makes an accept or a
     * claim made here fire exactly what the owning mod's own menu would - its toast, its follow-on
     * grants, its bookkeeping.
     */
    @Nullable
    private static <T> T scoped(@Nonnull Subject subject, @Nonnull Function<Subject, T> body) {
        try {
            ProgressionCallScope scope = ProgressionRuntime.questScope();
            return scope.around(subject, body);
        } catch (Throwable t) {
            SafeLog.warn("[commerce] a board action failed", t);
            return null;
        }
    }

    @Nonnull
    private ToastSpec completionToast(@Nonnull Quest quest) {
        try {
            ToastSpec spec = deps.completionToast().forCompleted(quest.id());
            if (spec != null) {
                return spec;
            }
        } catch (Throwable ignored) {
            // A consumer's toast failing costs its own line, never the hand-in that earned it.
        }
        return ToastSpec.of(ToastKind.REWARD, text("board.toast.completed"));
    }

    private boolean handOff(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        try {
            return deps.completion().handOff(quest.id(), boardId, store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[commerce] a contract completion hand-off failed", t);
            return false;
        }
    }

    // ==================== text ====================

    /** What a contract is CALLED, with a generated row's arguments resolved through the deps seam. */
    @Nonnull
    private Message bountyName(@Nonnull BountyRef ref) {
        return CommerceText.title(textOf(ref), deps.titleArgs(), text("board.bounty.untitled"));
    }

    @Nullable
    private static ContentTextAsset textOf(@Nonnull BountyRef ref) {
        return ref instanceof BountyAssetRef authored ? authored.asset().getText() : null;
    }

    /**
     * What one step reads as, on the same ladder every other surface over the shared runtime uses: a
     * registered text source first (so a consumer that names content still names these), then the
     * step's own authored key, then the untitled line.
     *
     * <p>The engine model carries no display text by design, which is why the authored asset is
     * reached for rather than the {@link ObjectiveDef} the engine walks.
     */
    @Nonnull
    private Message objectiveName(@Nonnull BountyRef ref, @Nonnull Quest quest,
            @Nonnull ObjectiveDef objective) {
        Message named = fromTextSources(quest.id(), objective.id());
        if (named != null) {
            return named;
        }
        if (ref instanceof BountyAssetRef authored) {
            ObjectiveLeafAsset leaf = authored.asset().objectivesOrEmpty().get(objective.id());
            String key = leaf == null ? null : CommerceText.trimToNull(leaf.getTextKey());
            if (key != null) {
                return ContentKeys.tr(key);
            }
        }
        return text("board.step.untitled");
    }

    /**
     * What any registered source calls one step, first non-null winning. Each is guarded on its own,
     * so one mod's broken naming costs its own rows and nobody else's.
     */
    @Nullable
    private static Message fromTextSources(@Nonnull String contentId, @Nonnull String objectiveId) {
        try {
            for (ProgressionTextSource source : ProgressionRuntime.textSources()) {
                try {
                    Message answer = source.objective(contentId, objectiveId);
                    if (answer != null) {
                        return answer;
                    }
                } catch (Throwable ignored) {
                    // One source's failure is not another's.
                }
            }
        } catch (Throwable ignored) {
            // No runtime yet reads as nobody knowing, which is the authored fallback below.
        }
        return null;
    }

    /**
     * The grade a contract carries ON THIS BOARD, read on the one ladder both screens use: what the
     * board wrote beside that band, then what a consumer ships for it, then this library's own word
     * for the common bands, then the band itself. Null when the contract is ungraded here, which is a
     * normal thing for a contract to be.
     */
    @Nullable
    private Message gradeLabel(@Nonnull BountyRef ref, @Nonnull BoardAssetSpec board) {
        String grade = CommerceText.normalize(ref.difficultyOn(board.boardId()));
        return grade.isEmpty() ? null
                : CommerceLabels.grade(board.asset(), grade, deps.titleArgs());
    }

    @Nonnull
    private Message sectionText(@Nonnull BoardSections.Section section) {
        return switch (section) {
            case READY -> text("board.section.ready");
            case TURN_IN -> text("board.section.turn_in");
            case ACTIVE -> text("board.section.active");
            case AVAILABLE -> text("board.section.available");
            case LOCKED -> text("board.section.locked");
            case SPENT -> text("board.section.spent");
            case DONE -> text("board.section.done");
        };
    }

    /** One colour palette for a row's dot and the detail panel's status line, so they cannot drift. */
    @Nonnull
    private static String dotColor(@Nonnull BoardSections.Section section) {
        return switch (section) {
            case READY, TURN_IN -> "#ffcc4a";
            case ACTIVE -> "#4a9eff";
            case AVAILABLE -> "#ffaa4a";
            case LOCKED -> "#96a9be";
            case SPENT -> "#c8a86a";
            case DONE -> "#4aff7f";
        };
    }

    /** A price as one readable phrase, for the line that asks a player to confirm paying it. */
    @Nonnull
    private Message priceLine(@Nonnull Cost price) {
        CurrencyEngine currencies = CommerceDefaults.currencyEngine();
        String primary = price.primaryCurrencyId();
        if (primary == null) {
            return Msg.raw("");
        }
        return CommerceChips.amountAndName(currencies, primary, price.amountOf(primary),
                deps.currencyNames());
    }

    private static void paintLocked(@Nonnull UICommandBuilder cmd, @Nonnull String selector) {
        UiRetint.retintButtonStates(cmd, selector, "#2b3240", "#2b3240", "#2b3240");
        ZigRichButton.color(cmd, selector, "#7d8895");
    }

    private static void setIcon(@Nonnull UICommandBuilder cmd, @Nonnull String slotSelector,
            @Nonnull String gridSelector, @Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            cmd.set(slotSelector + ".Visible", false);
            return;
        }
        try {
            cmd.set(gridSelector + ".Slots", List.of(new ItemGridSlot(new ItemStack(itemId, 1))));
            cmd.set(slotSelector + ".Visible", true);
        } catch (Throwable ignored) {
            cmd.set(slotSelector + ".Visible", false);
        }
    }

    private static void bindAction(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action), false);
    }

    @Nonnull
    private static String armKey(@Nonnull String bountyId) {
        return "reroll:" + bountyId;
    }

    @Nonnull
    private Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
