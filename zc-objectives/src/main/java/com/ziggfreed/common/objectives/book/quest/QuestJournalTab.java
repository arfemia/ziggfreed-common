package com.ziggfreed.common.objectives.book.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.objectives.book.BookActions;
import com.ziggfreed.common.objectives.book.BookContext;
import com.ziggfreed.common.objectives.book.BookState;
import com.ziggfreed.common.objectives.book.BookTab;
import com.ziggfreed.common.objectives.book.ObjectiveBookEventData;
import com.ziggfreed.common.objectives.book.ObjectiveBookMenu;
import com.ziggfreed.common.objectives.book.ObjectiveBookPage;
import com.ziggfreed.common.objectives.journal.QuestActions;
import com.ziggfreed.common.objectives.journal.QuestPresentation;
import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.objectives.journal.QuestSection;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.KitText;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerIndex;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.util.SafeLog;

/**
 * The book's Quests tab: the quest journal. A toolbar (four status segments, the category dropdown, the tag dropdown
 * while a quest carries a tag, the search row), the list of sections by state ({@link QuestReader#journal}), and the
 * selected quest's page ({@link QuestReader#page}) with its action bar bound once. Its document is {@link #DOCUMENT};
 * the shell builds one instance per page with the no-argument constructor, so the instance keeps what its last
 * build drew (the list's index) for the partial updates that follow on the same page.
 *
 * <p><b>What it answers itself</b>, keeping the scroll: a selection (two row styles swap, the page repaints); a
 * section head (its rows appended the first time, then shown or hidden: {@code SECTION_TOGGLE_IN_PLACE}); Show
 * more; Track and Untrack, and a hand-in that leaves the quest where it was (the list and the page repaint whole in
 * one partial, every row appended and bound in that same update). Accept, a completing hand-in and Collect move the
 * quest, so they reopen on the same state and the selection lands on it in its new section
 * ({@code QuestJournalPlan}'s moved-row rule). Everything else (filters, the search, Abandon) is the shell's reopen.
 * The verbs are the book's ({@link BookContext#verbs}); a press is dispatched on the quest's live state
 * ({@link QuestActions#dispatch}).
 */
public final class QuestJournalTab implements BookTab {

    /** The tab's document, appended into the shell's {@code #TabBody}. */
    public static final String DOCUMENT = "Pages/ZigBookJournal.ui";

    /**
     * Whether a section head opens and closes the section in a partial update (its rows appended and bound the first
     * time it opens). Spike SP7 proved it; the fallback is a reopen with the state kept (the scroll is lost): false.
     */
    static final boolean SECTION_TOGGLE_IN_PLACE = true;

    /** The tab's own action: a section's "Show N more" ({@code Section} names it). */
    static final String SHOW_MORE = "show_more";

    static final String TOOLBAR = "#Toolbar";
    static final String CATEGORY = "#CategoryDropdown";
    static final String TAG = "#TagDropdown";
    static final String SEARCH = "#Search";
    static final String SPLIT = "#Split";
    static final String LIST = "#List";
    static final String LIST_EMPTY = "#ListEmpty";
    static final String PAGE_CARD = "#PageCard";
    static final String PAGE = "#Page";
    static final String EMPTY = "#JournalEmpty";

    /** The status segments, in {@link QuestSection#STATUSES} order. */
    static final String[] SEGMENTS = {"#SegAll", "#SegProgress", "#SegAvailable", "#SegDone"};

    /** A page with no selectable line and its toggle bound once in build. */
    private static final DetailBindings DETAIL_BINDINGS = new DetailBindings() {
        @Nullable
        @Override
        public EventData line(DetailBlock b, DetailLine l) {
            return null;
        }

        @Nullable
        @Override
        public EventData toggle(DetailToggle t) {
            return null;
        }
    };

    /** The model the last build or partial painted (its sections uncapped), for the partials that follow. */
    @Nullable private LedgerModel model;

    /** Where the last paint put the list's rows and sections. */
    @Nullable private LedgerIndex index;

    /** Sections the player asked to show more of on this page, and how many rows each shows now. */
    private final Map<String, Integer> caps = new HashMap<>();

    @Nonnull
    @Override
    public String id() {
        return ObjectiveBookPage.TAB_QUESTS;
    }

    @Nonnull
    @Override
    public String document() {
        return DOCUMENT;
    }

    @Nullable
    @Override
    public String searchRow() {
        return SEARCH;
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull BookContext ctx) {
        model = null;
        index = null;
        caps.clear();
        Subject subject = ctx.questSubject();
        if (subject == null) {
            showNoQuests(ctx);
            return;
        }
        QuestReader reader = reader(ctx, subject);
        ctx.header().subtitle(reader.subtitle());
        ctx.header().stats(reader.stats());
        List<Quest> listed = reader.listed();
        if (listed.isEmpty()) {
            showNoQuests(ctx);
            return;
        }
        toolbar(ctx, reader, listed);
        DetailPainter.bindActionsOnce(ctx.events(), ctx.at(PAGE), slot -> switch (slot) {
            case PRIMARY -> ctx.binding(BookActions.PRIMARY);
            case SECONDARY -> ctx.binding(BookActions.TRACK);
            case DANGER -> ctx.binding(BookActions.ABANDON);
        }, ctx.binding(BookActions.TRACK));
        paintList(ctx, reader);
    }

    /** No quest at all (or nobody to read): the empty state across the body. Paints inside the tab's root only. */
    private static void showNoQuests(@Nonnull BookContext ctx) {
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(ctx.at(TOOLBAR) + ".Visible", false);
        cmd.set(ctx.at(SPLIT) + ".Visible", false);
        cmd.set(ctx.at(EMPTY) + ".Visible", true);
        EmptyStatePainter.paint(cmd, ctx.events(), ctx.at(EMPTY), new EmptyState(
                Picture.item(ObjectiveBookMenu.QUESTS_ICON), QuestReader.text("empty.title"),
                QuestReader.text("empty.line"), null), null);
    }

    private static void toolbar(@Nonnull BookContext ctx, @Nonnull QuestReader reader, @Nonnull List<Quest> listed) {
        UICommandBuilder cmd = ctx.cmd();
        UIEventBuilder events = ctx.events();
        BookState state = ctx.state();

        String current = QuestSection.normalizeStatus(state.status());
        for (int i = 0; i < SEGMENTS.length; i++) {
            String status = QuestSection.STATUSES[i];
            String segment = ctx.at(SEGMENTS[i]);
            SegmentPainter.set(cmd, segment, QuestReader.text("status." + status), status.equals(current),
                    ctx.viewer());
            events.addEventBinding(CustomUIEventBindingType.Activating, segment,
                    ctx.binding(BookActions.STATUS).append(BookState.KEY_ID, status));
        }

        // A dropdown entry is a String-only sink: a plain key goes for the client to resolve, anything else flat.
        List<String> categories = reader.categories(listed);
        List<DropdownEntryInfo> categoryEntries = new ArrayList<>();
        categoryEntries.add(entry(QuestReader.text("category.all"), BookState.ALL));
        String chosenCategory = BookState.ALL;
        for (String category : categories) {
            categoryEntries.add(entry(reader.categoryName(category), category));
            if (category.equalsIgnoreCase(state.category())) {
                chosenCategory = category;
            }
        }
        dropdown(ctx, CATEGORY, categoryEntries, chosenCategory, BookActions.CATEGORY);

        List<String> tags = new ArrayList<>(reader.tags(listed));
        boolean tagFilter = !BookState.ALL.equalsIgnoreCase(state.tag());
        if (tagFilter && tags.stream().noneMatch(t -> t.equalsIgnoreCase(state.tag()))) {
            // A filter on a tag nothing carries any more keeps its entry, or nothing could clear it.
            tags.add(state.tag());
        }
        cmd.set(ctx.at(TAG) + ".Visible", !tags.isEmpty());
        if (!tags.isEmpty()) {
            List<DropdownEntryInfo> tagEntries = new ArrayList<>();
            tagEntries.add(entry(QuestReader.text("tag.all"), BookState.ALL));
            String chosenTag = BookState.ALL;
            for (String tag : tags) {
                tagEntries.add(entry(reader.presentation().tagLabel(tag), tag));
                if (tag.equalsIgnoreCase(state.tag())) {
                    chosenTag = tag;
                }
            }
            dropdown(ctx, TAG, tagEntries, chosenTag, BookActions.TAG);
        }

        ZigSearchRow.wire(cmd, events, ctx.at(SEARCH), state.search(), BookState.KEY_SEARCH_INPUT,
                state.event(BookActions.SEARCH), state.event(BookActions.CLEAR_SEARCH));
    }

    private static void dropdown(@Nonnull BookContext ctx, @Nonnull String id, @Nonnull List<DropdownEntryInfo> entries,
            @Nonnull String value, @Nonnull String action) {
        String selector = ctx.at(id);
        SettingsUiUtil.populate(ctx.cmd(), selector, entries, value);
        ctx.events().addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                ctx.binding(action).append("@DropdownValue", selector + ".Value"), false);
    }

    @Nonnull
    private static DropdownEntryInfo entry(@Nonnull Message label, @Nonnull String value) {
        String key = label.getMessageId();
        FormattedMessage formatted = label.getFormattedMessage();
        boolean plainKey = key != null && (formatted.params == null || formatted.params.isEmpty())
                && (formatted.messageParams == null || formatted.messageParams.isEmpty());
        return plainKey ? new DropdownEntryInfo(LocalizableString.fromMessageId(key), value)
                : SettingsUiUtil.entry(UiText.flatten(label), value);
    }

    /**
     * The list and the page, painted whole from {@code reader}: in a build, or in a partial that repaints both (every
     * row and button it binds is appended in the same update). The selection the plan settles on is kept, so the
     * action bar's verbs act on the row the player sees selected.
     */
    private void paintList(@Nonnull BookContext ctx, @Nonnull QuestReader reader) {
        UICommandBuilder cmd = ctx.cmd();
        BookState state = ctx.state();
        LedgerModel journal = reader.journal(reader.listed(), state.status(), state.category(), state.tag(),
                state.search());
        model = journal;
        boolean empty = journal.isEmpty();
        cmd.set(ctx.at(LIST) + ".Visible", !empty);
        cmd.set(ctx.at(LIST_EMPTY) + ".Visible", empty);
        if (empty) {
            index = null;
            cmd.set(ctx.at(PAGE_CARD) + ".Visible", false);
            if (ctx.building()) {
                EmptyStatePainter.paint(cmd, ctx.events(), ctx.at(LIST_EMPTY), new EmptyState(Picture.NONE,
                        KitText.nothingMatches(), KitText.nothingMatchesLine(), new DetailAction(ActionSlot.PRIMARY,
                                KitText.clearFilters(), ActionLook.NORMAL, BookActions.CLEAR_FILTERS, null, true,
                                null)), ctx.binding(BookActions.CLEAR_FILTERS));
            }
            return;
        }
        String selected = QuestJournalPlan.selection(journal, state.openSections(), state.selectedId());
        if (selected != null && !selected.equals(state.selectedId())) {
            ctx.keep(state.withSelected(selected));
        }
        Set<String> open = QuestJournalPlan.openSections(journal, state.openSections(), selected);
        index = LedgerPainter.paint(cmd, ctx.events(), ctx.at(LIST), QuestJournalPlan.withCaps(journal, caps), open,
                selected, bindings(ctx, open), RowSize.STANDARD, ctx.viewer());
        paintPage(ctx, reader, selected == null ? null : reader.engine().quest(selected));
    }

    /** The selected quest's page, or the card hidden when nothing is selected. */
    private static void paintPage(@Nonnull BookContext ctx, @Nonnull QuestReader reader, @Nullable Quest quest) {
        ctx.cmd().set(ctx.at(PAGE_CARD) + ".Visible", quest != null);
        if (quest != null) {
            DetailPainter.paint(ctx.cmd(), ctx.events(), ctx.at(PAGE), reader.page(quest), DETAIL_BINDINGS,
                    ctx.viewer());
        }
    }

    /** A row selects, a head toggles (asking the other way round from how {@code open} paints it), more shows more. */
    @Nonnull
    private static LedgerBindings bindings(@Nonnull BookContext ctx, @Nonnull Set<String> open) {
        return new LedgerBindings() {
            @Override
            public EventData row(LedgerSection s, LedgerRow r) {
                return ctx.binding(BookActions.SELECT, r.id());
            }

            @Override
            public EventData section(LedgerSection s) {
                return ctx.sectionBinding(s.id(), LedgerPainter.isOpen(s, open));
            }

            @Override
            public EventData showMore(LedgerSection s) {
                return ctx.binding(SHOW_MORE).append(BookState.KEY_SECTION, s.id());
            }
        };
    }

    // ==================== events ====================

    @Override
    public boolean handle(@Nonnull BookContext ctx, @Nonnull String action, @Nonnull ObjectiveBookEventData data) {
        return switch (action) {
            case BookActions.SELECT -> select(ctx, data.id);
            case BookActions.SECTION -> toggleSection(ctx, firstNonBlank(data.section, data.id));
            case SHOW_MORE -> showMore(ctx, firstNonBlank(data.section, data.id));
            case BookActions.PRIMARY -> primary(ctx, firstNonBlank(data.id, ctx.state().selectedId()));
            case BookActions.TRACK -> track(ctx, firstNonBlank(data.id, ctx.state().selectedId()));
            default -> false;
        };
    }

    /** Select a painted row in place: the two rows swap styles and the page repaints. */
    private boolean select(@Nonnull BookContext ctx, @Nullable String id) {
        Subject subject = ctx.questSubject();
        LedgerIndex painted = index;
        if (subject == null || painted == null || id == null || painted.rowSelector(id) == null) {
            return false;
        }
        QuestReader reader = reader(ctx, subject);
        Quest quest = reader.engine().quest(id);
        if (quest == null) {
            return false;
        }
        LedgerPainter.select(ctx.cmd(), painted, painted.selectedRowId(), id, ctx.viewer());
        paintPage(ctx, reader, quest);
        ctx.keep(ctx.state().withSelected(id));
        ctx.sendPartial();
        return true;
    }

    /**
     * Open or close a section in place, the other way round from how it shows now (its live binding may carry a
     * stale ask after an earlier toggle on this page). The player's word is kept for every later reopen.
     */
    private boolean toggleSection(@Nonnull BookContext ctx, @Nullable String sectionId) {
        LedgerIndex painted = index;
        LedgerModel shown = model;
        if (!SECTION_TOGGLE_IN_PLACE || painted == null || shown == null || sectionId == null
                || painted.sectionSelector(sectionId) == null) {
            return false;
        }
        LedgerSection section = QuestJournalPlan.section(QuestJournalPlan.withCaps(shown, caps), sectionId);
        if (section == null) {
            return false;
        }
        boolean open = !painted.isOpen(sectionId);
        if (open) {
            LedgerPainter.openSection(ctx.cmd(), ctx.events(), painted, section,
                    bindings(ctx, ctx.state().openSections()));
        } else {
            LedgerPainter.closeSection(ctx.cmd(), painted, sectionId);
        }
        ctx.keep(ctx.state().withSection(sectionId, open));
        ctx.sendPartial();
        return true;
    }

    /** Show one section's next rows: the list repaints whole in a partial with that section's cap raised. */
    private boolean showMore(@Nonnull BookContext ctx, @Nullable String sectionId) {
        LedgerModel shown = model;
        LedgerSection section = shown == null || sectionId == null ? null
                : QuestJournalPlan.section(QuestJournalPlan.withCaps(shown, caps), sectionId);
        if (section == null) {
            ctx.reopen(ctx.state());
            return true;
        }
        caps.put(section.id(), section.cap() + LedgerSection.DEFAULT_CAP);
        return refresh(ctx);
    }

    /**
     * Accept, Hand in or Collect, by what the quest is now. A hand-in that leaves the quest in its section repaints in
     * place; anything that moves it reopens on the same state, so the selection lands on it in its new section.
     */
    private boolean primary(@Nonnull BookContext ctx, @Nullable String questId) {
        Subject subject = ctx.questSubject();
        Quest quest = subject == null || questId == null ? null : ProgressionRuntime.quests().quest(questId);
        if (quest == null) {
            return false;
        }
        QuestReader before = reader(ctx, subject);
        QuestSection was = before.sectionOf(quest);
        String verb = QuestActions.dispatch(ActionSlot.PRIMARY, quest, before);
        boolean stays = false;
        try {
            if (QuestActions.ACCEPT.equals(verb)) {
                ctx.verbs().accept(quest);
            } else if (QuestActions.COLLECT.equals(verb)) {
                ctx.verbs().collect(quest);
            } else if (QuestActions.HAND_IN.equals(verb)) {
                ctx.verbs().handIn(quest, null);
                stays = reader(ctx, subject).sectionOf(quest) == was;
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] the journal's '" + verb + "' failed: " + t.getMessage());
        }
        if (stays && refresh(ctx)) {
            return true;
        }
        ctx.reopen(ctx.state());
        return true;
    }

    /** Track or untrack, then repaint in place: the row's mark, the page's toggle and the header's count follow. */
    private boolean track(@Nonnull BookContext ctx, @Nullable String questId) {
        Subject subject = ctx.questSubject();
        Quest quest = subject == null || questId == null ? null : ProgressionRuntime.quests().quest(questId);
        if (quest == null) {
            return false;
        }
        try {
            ctx.verbs().toggleTrack(quest);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the journal's track toggle failed: " + t.getMessage());
        }
        if (!refresh(ctx)) {
            ctx.reopen(ctx.state());
        }
        return true;
    }

    /**
     * Repaint the header, the list and the page whole in one partial, from a fresh read. False (nothing sent) when
     * the last paint drew no list to repaint, so the caller reopens instead.
     */
    private boolean refresh(@Nonnull BookContext ctx) {
        Subject subject = ctx.questSubject();
        if (subject == null || index == null) {
            return false;
        }
        QuestReader reader = reader(ctx, subject);
        if (reader.listed().isEmpty()) {
            return false;
        }
        ctx.header().subtitle(reader.subtitle());
        ctx.header().stats(reader.stats());
        paintList(ctx, reader);
        if (index == null) {
            // The repaint left no row (a filter now hides the last one): the empty state needs a build.
            return false;
        }
        ctx.sendPartial();
        return true;
    }

    // ==================== helpers ====================

    @Nonnull
    private static QuestReader reader(@Nonnull BookContext ctx, @Nonnull Subject subject) {
        return QuestReader.of(ProgressionRuntime.quests(), subject, QuestPresentation.of(ctx.deps()), subject.id(),
                ctx.nowMs());
    }

    @Nullable
    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null || b.isBlank() ? null : b.trim();
    }
}
