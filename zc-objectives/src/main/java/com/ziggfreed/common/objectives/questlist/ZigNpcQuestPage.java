package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Entry;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a character has to offer: a list of their quests on the left, the one being read on the
 * right, and every lifecycle affordance a player standing in front of somebody expects - take it on,
 * hand it in here, collect it, drop it, pin it.
 *
 * <p>This is the GENERIC screen, driven entirely through the shared progression runtime and the open
 * offer table, so it renders a server's whole merged catalogue whoever authored each entry. A
 * consumer contributes what the library cannot know through {@link NpcQuestPageDeps} - a character's
 * name, its alias set, its theme, what follows a settled quest - and never a page of its own.
 *
 * <h2>Two lists, one character</h2>
 *
 * <p>The HERE list is what this character is holding out ({@link NpcOfferProviders}, asked over the
 * character's whole answer set) plus anything already being carried whose outstanding step resolves
 * here. The MINE list is everything the player is carrying or has finished but not collected,
 * wherever it came from, so a player who walked away from the giver can still see it.
 *
 * <h2>The routed hand-in</h2>
 *
 * <p>A ready quest never takes over a conversation on its own; wherever one surfaces as a clickable
 * option, the click ROUTES here with that quest highlighted. A highlighted quest is pinned to the
 * top of the list and is what the detail panel opens on - there is no scroll-to on a page, so being
 * the first row IS "take me to it". The hand-in itself is a press on this page, and an inline
 * dialogue turn-in happens only where an author wrote one.
 *
 * <h2>Instance state, on purpose</h2>
 *
 * <p>Unlike the objective book, this page KEEPS its selection, its tab and the exact row order it
 * last built, and reopens as {@code this}. That is what a scroll-preserving partial update needs: a
 * {@code sendUpdate} runs against the DOM the last full {@code build} produced, so a row index must
 * be one that build RECORDED, never one recomputed from a list whose ordering is state-dependent. A
 * recomputed index can land on a section heading, whose {@code #StatusDot} does not exist, and an
 * unresolved selector disconnects the player. When the recorded index is gone, every path falls back
 * to a full reopen instead.
 *
 * <p>EVERY exit path sends a response - a reopen, a partial update, or a close - or the client spins
 * forever.
 */
public final class ZigNpcQuestPage extends ToastablePage<NpcQuestEventData> {

    /** The list of what this character is holding out; also the default. */
    public static final String TAB_HERE = "here";

    /** The list of what the player is carrying, wherever it came from. */
    public static final String TAB_MINE = "mine";

    private static final String PAGE_TEMPLATE = "Pages/ZigNpcQuestPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigNpcQuestRow.ui";
    private static final String LINE_TEMPLATE = "Pages/ZigNpcQuestLine.ui";

    /** What a theme is offered to repaint: the panel carrying the list. */
    private static final String FRAME_SELECTOR = "#LeftPanel";

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.progression.lang} file). */
    private static final String DOMAIN = "progression.";

    /** A hard ceiling on list rows, so a very large catalogue cannot build an unbounded page. */
    private static final int MAX_ROWS = 200;

    /** A ceiling on the lines inside one detail section (steps, rewards, refusals). */
    private static final int MAX_LINES = 24;

    /** The marker {@link #builtRowOrder} carries where a section heading was drawn. */
    private static final String HEADER_ROW = "";

    // The selected row's accent, and the shared row style's own per-state colours to revert to.
    private static final String ROW_SELECTED_TINT = "#1a2d44";
    private static final String ROW_SELECTED_TEXT = "#ffffff";
    private static final String ROW_TINT = "#41506a";
    private static final String ROW_HOVER_TINT = "#5b6f8c";
    private static final String ROW_PRESSED_TINT = "#344156";
    private static final String ROW_TEXT = "#b6c9de";

    private static final String HEADER_TEXT = "#8696a8";
    private static final String LINE_DONE = "#7affa0";
    private static final String LINE_OPEN = "#c6d4e4";
    private static final String LINE_UNSTARTED = "#8fa6bd";
    private static final String LINE_REFUSAL = "#ff9944";

    // Active/inactive tab contrast, the same treatment the objective book uses.
    private static final String TAB_ACTIVE_TINT = "#5e86bd";
    private static final String TAB_ACTIVE_HOVER = "#6f97cf";
    private static final String TAB_ACTIVE_TEXT = "#ffffff";
    private static final String TAB_INACTIVE_TINT = "#2f3b49";
    private static final String TAB_INACTIVE_HOVER = "#445364";
    private static final String TAB_INACTIVE_TEXT = "#9fb0c2";

    @Nullable private final String npcId;

    @Nonnull private final NpcQuestPageDeps deps;

    /** The quest this page was ROUTED to, pinned top and preselected for as long as the page lives. */
    @Nullable private final String highlightQuestId;

    @Nullable private String selectedQuestId;

    @Nonnull private String activeTab = TAB_HERE;

    /**
     * The exact row order the last full build rendered, section headings included as
     * {@link #HEADER_ROW} markers so a quest's index is the one the client DOM actually holds.
     */
    private final List<String> builtRowOrder = new ArrayList<>();

    /** The character's answer set, resolved once per build and read by every question after it. */
    private Set<String> answersTo = Set.of();

    public ZigNpcQuestPage(@Nonnull PlayerRef playerRef, @Nullable String npcId,
            @Nullable String highlightQuestId, @Nonnull NpcQuestPageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, NpcQuestEventData.CODEC);
        this.npcId = trimToNull(npcId);
        this.highlightQuestId = trimToNull(highlightQuestId);
        this.deps = deps;
        // With nobody in front of the player there is no "here" to list, so the page opens on what
        // they are carrying rather than on an empty panel with a dead tab in front of it.
        if (this.npcId == null) {
            this.activeTab = TAB_MINE;
        }
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));

        this.answersTo = deps.answerSetOrOwn(npcId);
        cmd.set("#NpcHeader.TextSpans", headerText());

        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref, playerRef);
        if (subject == null) {
            // Nothing can be read for this player, so say so once rather than painting an empty list
            // that reads as "this character has nothing", which is a different sentence.
            cmd.set("#QuestCount.TextSpans", text("npcquests.count", 0));
            paintTabs(cmd, events);
            showEmpty(cmd, text("npcquests.empty.unavailable"));
            renderToastInto(cmd);
            return;
        }

        // Both engines document self-heal as "whenever a surface opens", and it matters most at a
        // character: a finished daily whose cooldown has elapsed must read as re-acceptable HERE
        // rather than staying stuck on "completed", and a standing-value step is settled in the same
        // pass, so a level gained since the quest was taken shows before the rows are ranked.
        selfHeal(engine, subject);

        List<Quest> quests = TAB_MINE.equals(activeTab)
                ? engine.activeAndUnclaimed(subject)
                : questsHere(subject, engine);
        // A routed quest that is not on this character's list still has to be reachable, so the page
        // opens on the list that does hold it rather than on an empty panel.
        if (highlightQuestId != null && !containsQuest(quests, highlightQuestId)
                && TAB_HERE.equals(activeTab)) {
            List<Quest> mine = engine.activeAndUnclaimed(subject);
            if (containsQuest(mine, highlightQuestId)) {
                this.activeTab = TAB_MINE;
                quests = mine;
            }
        }

        Map<String, Quest> byId = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Quest quest : quests) {
            byId.put(quest.id(), quest);
            entries.add(Entry.of(quest.id(), sectionOf(subject, engine, quest),
                    quest.id().equals(highlightQuestId)));
        }
        List<Entry> ordered = NpcQuestSections.sort(entries);

        cmd.set("#QuestCount.TextSpans", text("npcquests.count", ordered.size()));
        paintTabs(cmd, events);

        List<String> orderedIds = new ArrayList<>();
        for (Entry entry : ordered) {
            orderedIds.add(entry.questId());
        }
        this.selectedQuestId = NpcQuestSections.select(orderedIds, highlightQuestId, selectedQuestId);

        builtRowOrder.clear();
        if (ordered.isEmpty()) {
            showEmptyList(cmd);
            renderToastInto(cmd);
            return;
        }
        appendRows(cmd, events, subject, engine, ordered, byId);

        // Bound ONCE per build with no quest id: the handlers act on whatever the detail panel is
        // showing, so a partial update can swap the panel without needing a binding it cannot add.
        bindDetailButtons(events);
        Quest selected = selectedQuestId == null ? null : byId.get(selectedQuestId);
        if (selected != null) {
            renderDetail(cmd, subject, engine, selected);
        } else {
            cmd.set("#RightPanel.Visible", false);
        }
        renderToastInto(cmd);
    }

    /**
     * Get the page's markup onto the screen, through a consumer's theme where there is one.
     *
     * <p>Guarded, and the fallback is the plain append rather than nothing: a theme is decoration, and
     * a decoration that throws must not cost the player the whole screen. A theme that threw AFTER
     * appending would append twice, so the retry only runs when nothing landed.
     */
    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, FRAME_SELECTOR);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[progression] a page theme failed, so the npc quest page renders plain: "
                    + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    private void selfHeal(@Nonnull QuestEngine engine, @Nonnull Subject subject) {
        try {
            engine.selfHeal(subject);
        } catch (Throwable t) {
            SafeLog.warn("[progression] npc quest page self-heal failed", t);
        }
    }

    /** The character's name, else its raw id, else a plain title for a list with nobody in front of it. */
    @Nonnull
    private Message headerText() {
        Message name = deps.nameOrNull(npcId);
        if (name != null) {
            return name;
        }
        return npcId != null ? Msg.raw(npcId) : text("npcquests.title.none");
    }

    /**
     * What belongs on this character's list, from THREE questions asked of two authorities.
     *
     * <p>Which quests a character HANDS OUT is an authoring-layer association the runtime cannot
     * read, which is exactly what the offer table exists to answer. The other two are pure quest
     * state, so the engine answers both itself over the whole answer set: which quests point BACK
     * here, and which were TAKEN here - the accept site the engine records on every accept, which is
     * what makes "given here" engine data rather than something a consumer has to register.
     */
    @Nonnull
    private List<Quest> questsHere(@Nonnull Subject subject, @Nonnull QuestEngine engine) {
        Map<String, Quest> out = new LinkedHashMap<>();
        if (!answersTo.isEmpty()) {
            for (NpcOffer offer : NpcOfferProviders.offersAt(subject, answersTo)) {
                Quest quest = engine.quest(offer.id());
                if (quest != null) {
                    out.putIfAbsent(quest.id(), quest);
                }
            }
        }
        // Carried and finished-but-uncollected alike: a quest parked for collection at the character
        // it was taken from has to be reachable here, or nobody could ever collect it.
        for (Quest quest : engine.activeAndUnclaimed(subject)) {
            if (out.containsKey(quest.id())) {
                continue;
            }
            if (readyHere(subject, engine, quest) || takenHere(subject, engine, quest)) {
                out.put(quest.id(), quest);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Is this character where the quest's outstanding step resolves, under any id it answers to? */
    private boolean readyHere(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        for (String id : answersTo) {
            if (engine.readyToTurnInAt(subject, quest, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Did the player TAKE this quest here? Read straight off the accept site the engine recorded, so
     * a quest a character handed out is still that character's business while it is being carried,
     * whatever the offer table currently offers.
     *
     * <p>Compared case-insensitively, matching how the engine compares the same id everywhere else.
     */
    private boolean takenHere(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        String site = engine.acceptSiteOf(subject, quest.id());
        if (site == null || site.isBlank()) {
            return false;
        }
        for (String id : answersTo) {
            if (site.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first outstanding hand-in step that can be handed in AT this character, WITH the id it
     * answered under - the id the hand-in itself must then be performed at, since a quest collected
     * at its own site pays out there and then while the same hand-in from nowhere parks it.
     *
     * <p>Where the page was opened with nobody in front of the player, the search is the "somewhere
     * unlocked" form and the id is null, which is the same thing the objective book asks.
     */
    @Nullable
    private TurnIn turnInHere(@Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        if (answersTo.isEmpty()) {
            ObjectiveDef anywhere = engine.firstActiveTurnIn(subject, quest, null);
            return anywhere == null ? null : new TurnIn(anywhere, null);
        }
        for (String id : answersTo) {
            ObjectiveDef step = engine.firstActiveTurnIn(subject, quest, id);
            if (step != null) {
                return new TurnIn(step, id);
            }
        }
        return null;
    }

    /**
     * Whether a FINISHED quest may be collected at this character, asked once per id it answers to.
     *
     * <p>The engine compares ONE id, deliberately, so a character answering to several is the
     * caller's loop - which is what keeps an identity registry out of the progression module. A quest
     * naming no collection site passes on the first ask, which is the great majority of content.
     */
    private boolean canCollectHere(@Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        if (answersTo.isEmpty()) {
            return engine.canCompleteAt(subject, quest, null);
        }
        return collectionSite(subject, engine, quest) != null;
    }

    /**
     * The id, out of this character's answer set, this quest may be collected under - the one the
     * claim itself must be made at. Null when none of them answers, and null when there is nobody in
     * front of the player at all, which is exactly what a claim from nowhere passes.
     */
    @Nullable
    private String collectionSite(@Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        for (String id : answersTo) {
            if (engine.canCompleteAt(subject, quest, id)) {
                return id;
            }
        }
        return null;
    }

    @Nonnull
    private Section sectionOf(@Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        QuestStatus status = engine.status(subject, quest);
        boolean acceptable = status == QuestStatus.NOT_STARTED
                && engine.canAccept(subject, quest).allowed();
        return NpcQuestSections.classify(status, acceptable, readyHere(subject, engine, quest),
                canCollectHere(subject, engine, quest));
    }

    /** One outstanding hand-in, and the id this character answered under when it was found. */
    private record TurnIn(@Nonnull ObjectiveDef step, @Nullable String atId) {
    }

    // ==================== the list ====================

    private void appendRows(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull List<Entry> ordered,
            @Nonnull Map<String, Quest> byId) {
        Section open = null;
        int index = 0;
        for (Entry entry : ordered) {
            Quest quest = byId.get(entry.questId());
            if (quest == null) {
                continue;
            }
            // A highlighted row is pinned above its own section, so drawing that section's heading in
            // front of it would file it under a group it has jumped out of.
            boolean opensSection = !entry.highlighted() && entry.section() != open;
            // A heading and the row it heads are budgeted TOGETHER, so a list cut short by the row
            // ceiling never ends on a heading with nothing under it.
            if (index + (opensSection ? 1 : 0) >= MAX_ROWS) {
                break;
            }
            if (opensSection) {
                open = entry.section();
                index = appendHeader(cmd, index, sectionText(entry.section()));
            }
            index = appendQuestRow(cmd, events, index, entry, quest);
        }
    }

    /** A heading, drawn as a row whose button is hidden and whose label carries the group's name. */
    private int appendHeader(@Nonnull UICommandBuilder cmd, int index, @Nonnull Message label) {
        String sel = appendRow(cmd, index);
        builtRowOrder.add(HEADER_ROW);
        cmd.set(sel + " #QuestBtn.Visible", false);
        cmd.set(sel + " #StatusDot.Visible", false);
        cmd.set(sel + " #SectionLabel.TextSpans", label);
        cmd.set(sel + " #SectionLabel.Style.TextColor", HEADER_TEXT);
        cmd.set(sel + " #SectionLabel.Visible", true);
        return index + 1;
    }

    private int appendQuestRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull Entry entry, @Nonnull Quest quest) {
        String sel = appendRow(cmd, index);
        builtRowOrder.add(quest.id());
        ZigRichButton.text(cmd, sel + " #QuestBtn", questName(quest.id()));
        cmd.set(sel + " #StatusDot.Background", dotColor(entry.section()));
        if (quest.id().equals(selectedQuestId)) {
            paintRowSelected(cmd, sel, true);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #QuestBtn",
                EventData.of("Action", "select").append("QuestId", quest.id()), false);
        return index + 1;
    }

    @Nonnull
    private static String appendRow(@Nonnull UICommandBuilder cmd, int index) {
        cmd.append("#QuestList", ROW_TEMPLATE);
        return "#QuestList[" + index + "]";
    }

    /**
     * The selected row's accent, and the exact per-state colours of the shared row style to revert
     * to. Tinting the button's states is what a partial update can do; replacing its background with
     * a bare string is what red-Xes a patch style.
     */
    private static void paintRowSelected(@Nonnull UICommandBuilder cmd, @Nonnull String rowSel,
            boolean selected) {
        if (selected) {
            UiRetint.retintButtonStates(cmd, rowSel + " #QuestBtn",
                    ROW_SELECTED_TINT, ROW_SELECTED_TINT, ROW_SELECTED_TINT);
            ZigRichButton.color(cmd, rowSel + " #QuestBtn", ROW_SELECTED_TEXT);
            cmd.set(rowSel + " #QuestBtn #Label.Style.RenderBold", true);
        } else {
            UiRetint.retintButtonStates(cmd, rowSel + " #QuestBtn",
                    ROW_TINT, ROW_HOVER_TINT, ROW_PRESSED_TINT);
            ZigRichButton.color(cmd, rowSel + " #QuestBtn", ROW_TEXT);
            cmd.set(rowSel + " #QuestBtn #Label.Style.RenderBold", false);
        }
    }

    /**
     * The empty-state line, with the tab bar left visible and bound: a whole-page empty state would
     * hide the only route back to the other list.
     */
    private void showEmptyList(@Nonnull UICommandBuilder cmd) {
        showEmpty(cmd, text(TAB_MINE.equals(activeTab)
                ? "npcquests.empty.mine" : "npcquests.empty.here"));
    }

    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull Message line) {
        cmd.set("#EmptyListLabel.TextSpans", line);
        cmd.set("#EmptyListLabel.Visible", true);
        cmd.set("#RightPanel.Visible", false);
    }

    private void paintTabs(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        boolean mine = TAB_MINE.equals(activeTab);
        ZigRichButton.text(cmd, "#TabHere", text("npcquests.tab.here"));
        ZigRichButton.text(cmd, "#TabMine", text("npcquests.tab.mine"));
        bindTab(events, "#TabHere", TAB_HERE);
        bindTab(events, "#TabMine", TAB_MINE);
        styleTab(cmd, "#TabHere", !mine);
        styleTab(cmd, "#TabMine", mine);
    }

    private static void bindTab(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String target) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", "tab").append("Tab", target), false);
    }

    private static void styleTab(@Nonnull UICommandBuilder cmd, @Nonnull String selector, boolean active) {
        String base = active ? TAB_ACTIVE_TINT : TAB_INACTIVE_TINT;
        UiRetint.retintButtonStates(cmd, selector, base,
                active ? TAB_ACTIVE_HOVER : TAB_INACTIVE_HOVER, base);
        ZigRichButton.color(cmd, selector, active ? TAB_ACTIVE_TEXT : TAB_INACTIVE_TEXT);
    }

    // ==================== the detail panel ====================

    /**
     * Paint the right-hand panel for {@code quest}. IDEMPOTENT, so a partial update can re-render it
     * in place for a newly selected or just-changed quest: it clears every appended container first
     * and sets EVERY conditional control's visibility explicitly, so nothing from the previous quest
     * survives.
     */
    private void renderDetail(@Nonnull UICommandBuilder cmd, @Nonnull Subject subject,
            @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        cmd.clear("#ObjectivesSection");
        cmd.clear("#RewardsList");
        cmd.clear("#RequirementsSection");
        cmd.set("#RightPanel.Visible", true);
        cmd.set("#AcceptBtn.Visible", false);
        cmd.set("#TurnInBtn.Visible", false);
        cmd.set("#ClaimBtn.Visible", false);
        cmd.set("#AbandonBtn.Visible", false);
        cmd.set("#TrackBtn.Visible", false);
        cmd.set("#RewardsHeader.Visible", false);
        cmd.set("#RequirementsHeader.Visible", false);
        cmd.set("#Flavor.Visible", false);

        QuestStatus status = engine.status(subject, quest);
        Section section = sectionOf(subject, engine, quest);

        cmd.set("#DetailTitle.TextSpans", questName(quest.id()));
        cmd.set("#DetailStatus.TextSpans", sectionText(section));
        cmd.set("#DetailStatus.Style.TextColor", dotColor(section));

        // The narrative this quest reads with where it stands, when any text source has one, and the
        // one line under the title otherwise. Most content has only the latter.
        Message body = ProgressionTexts.lore(quest.id(), loreState(status));
        if (body == null) {
            body = ProgressionTexts.flavor(quest.id());
        }
        if (body != null) {
            cmd.set("#Flavor.TextSpans", body);
            cmd.set("#Flavor.Visible", true);
        }

        renderObjectives(cmd, subject, engine, quest, status);
        renderRewards(cmd, quest);

        switch (section) {
            case AVAILABLE -> {
                ZigRichButton.text(cmd, "#AcceptBtn", text("book.action.accept"));
                cmd.set("#AcceptBtn.Visible", true);
            }
            case LOCKED -> renderRefusals(cmd, engine.canAccept(subject, quest).reasons());
            case READY -> {
                ZigRichButton.text(cmd, "#ClaimBtn", text("book.action.claim"));
                cmd.set("#ClaimBtn.Visible", true);
            }
            case PARKED -> {
                // Finished, and its rewards belong at a character this one is not. The status line
                // has already said so; offering a button that would refuse says it worse.
            }
            case TURN_IN, ACTIVE -> {
                TurnIn turnIn = turnInHere(subject, engine, quest);
                if (turnIn != null) {
                    // A report-back hand-in delivers nothing, so it reads as finishing the step
                    // rather than as handing something over.
                    ZigRichButton.text(cmd, "#TurnInBtn",
                            text(turnIn.step().target().isEmpty()
                                    ? "npcquests.action.complete" : "book.action.turn_in"));
                    cmd.set("#TurnInBtn.Visible", true);
                }
                ZigRichButton.text(cmd, "#AbandonBtn", text("npcquests.action.abandon"));
                cmd.set("#AbandonBtn.Visible", true);
                renderTrack(cmd, subject, engine, quest);
            }
            case DONE -> {
                // Finished and collected: there is nothing left to press.
            }
        }
    }

    /**
     * The pin, offered only on a quest being CARRIED: what a pinned quest means is "keep this in
     * front of me while I work on it", which a finished one has nothing left to be.
     */
    private void renderTrack(@Nonnull UICommandBuilder cmd, @Nonnull Subject subject,
            @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        boolean pinned = engine.tracked(subject).contains(quest.id());
        ZigRichButton.text(cmd, "#TrackBtn",
                text(pinned ? "npcquests.action.untrack" : "npcquests.action.track"));
        cmd.set("#TrackBtn.Visible", true);
    }

    /**
     * Every step, with its count where the player is carrying the quest and without one where they
     * are not - a quest being read BEFORE it is taken shows what it asks for, not a wall of zeroes.
     */
    private void renderObjectives(@Nonnull UICommandBuilder cmd, @Nonnull Subject subject,
            @Nonnull QuestEngine engine, @Nonnull Quest quest, @Nonnull QuestStatus status) {
        cmd.set("#ObjectivesHeader.TextSpans", text("npcquests.header.objectives"));
        boolean carried = status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED;
        Map<String, ObjectiveProgressState> progress = carried
                ? engine.progressOf(subject, quest.id()) : Map.of();
        List<ObjectiveDef> objectives = quest.objectives();
        int shown = Math.min(objectives.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            ObjectiveDef objective = objectives.get(i);
            Message name = ProgressionTexts.objective(quest.id(), objective.id());
            if (name == null) {
                name = text("book.quests.step.untitled");
            }
            String sel = appendLine(cmd, "#ObjectivesSection", i);
            if (!carried) {
                setLine(cmd, sel, name, LINE_UNSTARTED);
                continue;
            }
            ObjectiveProgressState state = progress.get(objective.id());
            int current = state != null ? state.current() : 0;
            int required = state != null ? state.required() : objective.amountAsInt();
            boolean done = state != null && state.isCompleted();
            setLine(cmd, sel, Msg.join(name, Msg.raw("  "), text("book.progress", current, required)),
                    done ? LINE_DONE : LINE_OPEN);
        }
    }

    private void renderRewards(@Nonnull UICommandBuilder cmd, @Nonnull Quest quest) {
        List<RewardChip> chips = NpcQuestRewardChips.chipsFor(quest.rewards(), deps.rewardChips());
        if (chips.isEmpty()) {
            return;
        }
        cmd.set("#RewardsHeader.TextSpans", text("npcquests.header.rewards"));
        cmd.set("#RewardsHeader.Visible", true);
        int shown = Math.min(chips.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            RewardChip chip = chips.get(i);
            String sel = appendLine(cmd, "#RewardsList", i);
            setLine(cmd, sel, chip.label(), LINE_OPEN);
            if (chip.hasIcon()) {
                cmd.set(sel + " #LineIcon.Slots",
                        List.of(new ItemGridSlot(new ItemStack(chip.iconItemId(), 1))));
                cmd.set(sel + " #LineIconSlot.Visible", true);
            }
        }
    }

    /**
     * Why a visible quest cannot be taken, so a locked row explains itself instead of sitting inert.
     *
     * <p>Only the refusals a player can ACT on have a line of their own; anything else - a spent
     * calendar window, a spent lifetime cap, a gate evaluator's own reason, which belongs to whoever
     * authored the gate - reads as the generic line rather than leaking an internal token at a
     * player. The same three the objective book keys, so the two surfaces cannot disagree.
     */
    private void renderRefusals(@Nonnull UICommandBuilder cmd, @Nonnull List<String> reasons) {
        // Several refusals can map to one line, and a list repeating the same sentence three times
        // reads as a bug rather than as emphasis.
        Set<String> keys = new LinkedHashSet<>();
        for (String reason : reasons) {
            keys.add(lockKey(reason));
        }
        List<Message> lines = new ArrayList<>();
        for (String key : keys) {
            lines.add(text(key));
        }
        if (lines.isEmpty()) {
            return;
        }
        cmd.set("#RequirementsHeader.TextSpans", text("npcquests.header.required"));
        cmd.set("#RequirementsHeader.Visible", true);
        int index = 0;
        for (Message line : lines) {
            if (index >= MAX_LINES) {
                break;
            }
            setLine(cmd, appendLine(cmd, "#RequirementsSection", index), line, LINE_REFUSAL);
            index++;
        }
    }

    @Nonnull
    private static String lockKey(@Nullable String reason) {
        if (QuestGates.REASON_UNAVAILABLE.equals(reason)) {
            return "book.quests.lock.unavailable";
        }
        if (QuestGates.REASON_ON_COOLDOWN.equals(reason)) {
            return "book.quests.lock.on_cooldown";
        }
        if (QuestGates.REASON_PREREQUISITES.equals(reason)) {
            return "book.quests.lock.prerequisites";
        }
        return "book.quests.lock.other";
    }

    @Nonnull
    private static String appendLine(@Nonnull UICommandBuilder cmd, @Nonnull String container, int index) {
        cmd.append(container, LINE_TEMPLATE);
        return container + "[" + index + "]";
    }

    private static void setLine(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
            @Nonnull Message text, @Nonnull String color) {
        cmd.set(sel + " #LineText.TextSpans", text);
        cmd.set(sel + " #LineText.Style.TextColor", color);
    }

    private void bindDetailButtons(@Nonnull UIEventBuilder events) {
        bindAction(events, "#AcceptBtn", "accept");
        bindAction(events, "#TurnInBtn", "turnIn");
        bindAction(events, "#ClaimBtn", "claim");
        bindAction(events, "#AbandonBtn", "abandon");
        bindAction(events, "#TrackBtn", "track");
    }

    private static void bindAction(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action), false);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull NpcQuestEventData data) {
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
            this.activeTab = TAB_MINE.equals(data.tab) ? TAB_MINE : TAB_HERE;
            // The new list auto-selects its own first row, unless the routed quest is on it.
            this.selectedQuestId = null;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if ("select".equals(action)) {
            selectQuest(ref, store, player, data.questId);
            return;
        }

        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref, playerRef);
        Quest quest = selectedQuestId == null ? null : engine.quest(selectedQuestId);
        if (subject == null || quest == null) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if ("turnIn".equals(action)) {
            // The hand-in owns its own response, because a settled quest may hand the screen over to
            // whatever comes next instead of returning to this page.
            turnIn(ref, store, player, subject, engine, quest);
            return;
        }
        if ("accept".equals(action)) {
            accept(subject, engine, quest);
        } else if ("claim".equals(action)) {
            claim(subject, engine, quest);
        } else if ("abandon".equals(action)) {
            abandon(subject, engine, quest);
        } else if ("track".equals(action)) {
            track(subject, engine, quest);
        }
        refreshOrReopen(ref, store, player, subject, engine, quest);
    }

    /**
     * Swap the highlighted row and re-render the detail panel in place, so the list keeps its scroll
     * position. Falls back to a full reopen when the clicked row is not one the last build recorded,
     * because a recomputed index can address a different row entirely.
     */
    private void selectQuest(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String questId) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = questId == null ? null : engine.quest(questId);
        int row = questId == null ? -1 : builtRowOrder.indexOf(questId);
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref, playerRef);
        if (quest == null || row < 0 || subject == null) {
            this.selectedQuestId = questId;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        String previous = this.selectedQuestId;
        this.selectedQuestId = questId;
        UICommandBuilder cmd = new UICommandBuilder();
        int oldRow = previous == null ? -1 : builtRowOrder.indexOf(previous);
        if (oldRow >= 0 && oldRow != row) {
            paintRowSelected(cmd, "#QuestList[" + oldRow + "]", false);
        }
        paintRowSelected(cmd, "#QuestList[" + row + "]", true);
        renderDetail(cmd, subject, engine, quest);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /**
     * Take the quest on, RECORDING that it was taken here.
     *
     * <p>The site is what makes a quest that must be settled where it was taken work at all, and what
     * puts it on this character's tab while it is being carried. Passing it always is deliberate: the
     * content decides whether it matters, and a surface that decided for it would have to know.
     */
    private void accept(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        ProgressionCallScope scope = ProgressionRuntime.questScope();
        boolean ok = Boolean.TRUE.equals(scope.around(subject, s ->
                Boolean.valueOf(engine.canAccept(s, quest).allowed() && engine.accept(s, quest, npcId))));
        showToast(ok ? ToastKind.SUCCESS : ToastKind.WARNING,
                text(ok ? "book.toast.accepted" : "book.toast.accept_failed"));
    }

    /**
     * Collect a parked quest AT the id this character answered under. The engine re-checks the site
     * itself, so a quest belonging somewhere else refuses here even if a stale screen offered it.
     */
    private void claim(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        String site = collectionSite(subject, engine, quest);
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope()
                .around(subject, s -> Boolean.valueOf(engine.claim(s, quest, site))));
        showToast(ok ? ToastKind.REWARD : ToastKind.WARNING,
                text(ok ? "book.toast.claimed" : "book.toast.claim_failed"));
    }

    private void abandon(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope()
                .around(subject, s -> Boolean.valueOf(engine.abandon(s, quest.id()))));
        if (ok) {
            showToast(ToastKind.INFO, text("npcquests.toast.abandoned"));
        }
    }

    private void track(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        if (engine.tracked(subject).contains(quest.id())) {
            engine.untrack(subject, quest.id());
            showToast(ToastKind.INFO, text("npcquests.toast.untracked"));
            return;
        }
        boolean ok = engine.track(subject, quest.id());
        showToast(ok ? ToastKind.SUCCESS : ToastKind.WARNING,
                text(ok ? "npcquests.toast.tracked" : "npcquests.toast.track_full"));
    }

    /**
     * Hand in the outstanding step here, and answer the player one way or another on every path.
     *
     * <p>The button is offered whenever a step is outstanding rather than only when the player is
     * carrying everything, so a shortfall is answered with what is still owed instead of a control
     * that is silently not there.
     */
    private void turnIn(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        TurnIn turnIn = turnInHere(subject, engine, quest);
        if (turnIn == null) {
            refreshOrReopen(ref, store, player, subject, engine, quest);
            return;
        }
        // Handed in AT the id this character answered under: the hand-in that finishes a quest at its
        // own collection site pays out there and then, while the same hand-in from nowhere parks it.
        Integer handed = ProgressionRuntime.questScope().around(subject,
                s -> Integer.valueOf(engine.attemptTurnIn(s, quest, turnIn.step().id(), turnIn.atId())));
        if (handed == null || handed.intValue() <= 0) {
            ObjectiveProgressState state = engine.progressOf(subject, quest.id(), turnIn.step().id());
            showToast(ToastKind.WARNING, state == null
                    ? text("book.toast.turn_in_failed")
                    : text("npcquests.toast.turn_in_short", state.current(), state.required()));
            refreshOrReopen(ref, store, player, subject, engine, quest);
            return;
        }
        if (engine.status(subject, quest) == QuestStatus.ACTIVE) {
            showToast(ToastKind.SUCCESS, text("book.toast.turned_in"));
            refreshOrReopen(ref, store, player, subject, engine, quest);
            return;
        }

        // ORDER IS LOAD-BEARING: the toast goes up FIRST, because whatever the hand-off opens
        // repaints the shared per-player toast state, so showing it afterwards would post it to a
        // screen that has already gone.
        showToast(completionToast(quest));
        // What follows a settled quest is the routing layer's decision, never this page's: the giver
        // reacts, or nothing does because the quest names no conversation, nobody carries it, or
        // there is nobody in front of the player. False means nothing was painted, so this page
        // still owes the player a response.
        if (!handOff(quest, store, ref, player)) {
            refreshOrReopen(ref, store, player, subject, engine, quest);
        }
    }

    @Nonnull
    private ToastSpec completionToast(@Nonnull Quest quest) {
        try {
            ToastSpec spec = deps.completionToast().forCompleted(quest);
            if (spec != null) {
                return spec;
            }
        } catch (Throwable ignored) {
            // A consumer's toast failing costs its own line, never the hand-in that earned it.
        }
        return ToastSpec.of(ToastKind.SUCCESS, text("book.toast.turned_in"));
    }

    private boolean handOff(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        try {
            return deps.completion().handOff(quest.id(), npcId, store, ref, playerRef, player);
        } catch (Throwable t) {
            SafeLog.warn("[progression] a quest completion hand-off failed", t);
            return false;
        }
    }

    /**
     * Refresh the acted-on quest's row and detail in place, scroll preserved; reopen when the quest
     * has left this list or its row is not one the last build recorded.
     */
    private void refreshOrReopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        if (staysInList(subject, engine, quest) && sendSelectedUpdate(subject, engine, quest)) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, this);
    }

    /**
     * Is the quest still on the list currently being shown? The HERE list keeps everything it drew
     * (a re-acceptable or locked row is still this character's business), while the MINE list holds
     * only what is being carried - so a quest just claimed or abandoned leaves it and the page has to
     * rebuild rather than leave a row for something the list no longer contains.
     */
    private boolean staysInList(@Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        if (!TAB_MINE.equals(activeTab)) {
            return true;
        }
        return containsQuest(engine.activeAndUnclaimed(subject), quest.id());
    }

    private boolean sendSelectedUpdate(@Nonnull Subject subject, @Nonnull QuestEngine engine,
            @Nonnull Quest quest) {
        int row = builtRowOrder.indexOf(quest.id());
        if (row < 0) {
            return false;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#QuestList[" + row + "] #StatusDot.Background",
                dotColor(sectionOf(subject, engine, quest)));
        renderDetail(cmd, subject, engine, quest);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }

    // ==================== text ====================

    @Nonnull
    private Message questName(@Nonnull String questId) {
        Message name = ProgressionTexts.title(questId);
        return name != null ? name : text("book.quests.untitled");
    }

    @Nonnull
    private Message sectionText(@Nonnull Section section) {
        return switch (section) {
            case READY -> text("npcquests.section.ready");
            case TURN_IN -> text("npcquests.section.turn_in");
            case ACTIVE -> text("npcquests.section.active");
            case AVAILABLE -> text("npcquests.section.available");
            case PARKED -> text("npcquests.section.parked");
            case LOCKED -> text("npcquests.section.locked");
            case DONE -> text("npcquests.section.done");
        };
    }

    /** One colour palette for a row's dot and the detail panel's status line, so they cannot drift. */
    @Nonnull
    private static String dotColor(@Nonnull Section section) {
        return switch (section) {
            case READY -> "#ffcc4a";
            case TURN_IN -> "#ffcc4a";
            case ACTIVE -> "#4a9eff";
            case AVAILABLE -> "#ffaa4a";
            case PARKED -> "#c8a86a";
            case LOCKED -> "#96a9be";
            case DONE -> "#4aff7f";
        };
    }

    /**
     * Which narrative a quest reads with where it stands: the lower-case lifecycle word the shared
     * text seam is asked for, matching the convention keys content already uses.
     */
    @Nonnull
    private static String loreState(@Nonnull QuestStatus status) {
        return switch (status) {
            case ACTIVE -> "active";
            case COMPLETED, COMPLETED_UNCLAIMED -> "complete";
            case NOT_STARTED, ON_COOLDOWN -> "incomplete";
        };
    }

    @Nonnull
    private Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    private static boolean containsQuest(@Nonnull List<Quest> quests, @Nonnull String questId) {
        for (Quest quest : quests) {
            if (quest.id().equals(questId)) {
                return true;
            }
        }
        return false;
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
