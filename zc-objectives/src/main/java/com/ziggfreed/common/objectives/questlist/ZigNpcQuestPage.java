package com.ziggfreed.common.objectives.questlist;

import java.util.HashMap;
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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.objectives.book.ObjectiveBookMenu;
import com.ziggfreed.common.objectives.journal.QuestActions;
import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.objectives.render.ClaimToasts;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerIndex;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a character has to offer: a list of their quests on the left, the one being read on the right, and every
 * lifecycle affordance a player standing in front of somebody expects - take it on, hand it in here, collect it,
 * drop it, track it.
 *
 * <p>This is the GENERIC screen, driven entirely through the shared progression runtime and the open offer table, so
 * it renders a server's whole merged catalogue whoever authored each entry. A consumer contributes what the library
 * cannot know through {@link NpcQuestPageDeps} - a character's name, its alias set, its theme, what follows a settled
 * quest - and never a page of its own.
 *
 * <h2>The book's quest page, at the giver</h2>
 *
 * <p>The list is the kit's ledger ({@link LedgerPainter}) and the right-hand side is the kit's reading page
 * ({@link DetailPainter}), built from the same {@link QuestReader} as the Objective Book's Quests tab, so a quest
 * reads identically here and in the book and one fix lands in both. What differs is the place: the reader is handed
 * this character's {@link CharacterQuestListing}, so the action bar offers Accept for what is Available here (a
 * giver-bound quest is taken at its giver), Hand in where a step resolves here, and Collect only where the quest may
 * be collected ({@link QuestActions}).
 *
 * <h2>Two lists, one character</h2>
 *
 * <p>The HERE list is what this character is holding out ({@link NpcOfferProviders}, asked over the character's whole
 * answer set) plus anything already being carried whose business is here. The MINE list is everything the player is
 * carrying or has finished but not collected, wherever it came from, so a player who walked away from the giver can
 * still see it. Both are sectioned by what the player can do about each quest ({@link NpcQuestSections}).
 *
 * <h2>The routed hand-in</h2>
 *
 * <p>A ready quest never takes over a conversation on its own; wherever one surfaces as a clickable option, the click
 * ROUTES here with that quest highlighted. A highlighted quest leads the list in its own open section and is what the
 * page opens on - there is no scroll-to on a page, so being the first row IS "take me to it"
 * ({@code NpcQuestPagePlan}).
 *
 * <h2>Instance state, on purpose</h2>
 *
 * <p>This page KEEPS its list, selection, open sections and the {@link LedgerIndex} its last full paint returned, and
 * reopens as {@code this}: a {@code sendUpdate} runs against the DOM the last build produced, so a selection moves
 * through the index that build recorded, never one recomputed. An action repaints the whole list and the page in one
 * partial update (every element it binds is appended in that same update), so a quest that moved section is drawn
 * under its new head with the scroll kept. The three action buttons and the header toggle are bound ONCE per build
 * with no quest id and are dispatched on the live state of whatever the page shows.
 *
 * <p>EVERY exit path sends a response - a reopen, a partial update, or a close - or the client spins forever.
 */
public final class ZigNpcQuestPage extends ToastablePage<NpcQuestEventData> {

    /** The list of what this character is holding out; also the default. */
    public static final String TAB_HERE = NpcQuestPagePlan.TAB_HERE;

    /** The list of what the player is carrying, wherever it came from. */
    public static final String TAB_MINE = NpcQuestPagePlan.TAB_MINE;

    /**
     * Whether an action, a section's fold and "Show more" repaint the list in a partial update (appending sections
     * and rows into the live list), or reopen the page with its state kept. The one switch for that mechanism.
     */
    static final boolean LIST_IN_PLACE = true;

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.progression.lang} file). */
    private static final String DOMAIN = "progression.";

    // The actions the page's bindings carry.
    private static final String CLOSE = "close";
    private static final String TAB = "tab";
    private static final String SELECT = "select";
    private static final String SECTION = "section";
    private static final String MORE = "more";
    private static final String LINE = "line";
    private static final String PRIMARY = "primary";
    private static final String SECONDARY = "secondary";
    private static final String DANGER = "danger";
    private static final String TOGGLE = "toggle";

    @Nullable private final String npcId;

    @Nonnull private final NpcQuestPageDeps deps;

    /** The quest this page was ROUTED to, leading the list and preselected for as long as the page lives. */
    @Nullable private final String highlightQuestId;

    @Nullable private String selectedQuestId;

    @Nonnull private String activeTab = TAB_HERE;

    /** Whether the routed quest has had its say about which list opens; only the first build asks. */
    private boolean routed;

    /**
     * Whether the routed quest still picks what the page opens on: on the first paint of a list, and never again
     * once the player has chosen, so an action on another quest does not jump the page back to the routed one.
     */
    private boolean routeSelects = true;

    /** The player's open and closed sections ({@link LedgerPainter#withSection}), for the list on screen. */
    @Nonnull private Set<String> openSections = new LinkedHashSet<>();

    /** How many rows each section shows, by section id, once "Show more" was pressed. */
    @Nonnull private Map<String, Integer> caps = new HashMap<>();

    /** The list the last paint drew. */
    @Nullable private LedgerModel model;

    /** Where the last paint put every row and section; null when the list was empty. */
    @Nullable private LedgerIndex index;

    /** The character's answer set, resolved once per build and read by every question after it. */
    private Set<String> answersTo = Set.of();

    private final LedgerBindings ledgerBindings = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return EventData.of("Action", SELECT).append("QuestId", r.id());
        }

        @Override
        public EventData section(LedgerSection s) {
            return EventData.of("Action", SECTION).append("Section", s.id());
        }

        @Override
        public EventData showMore(LedgerSection s) {
            return EventData.of("Action", MORE).append("Section", s.id());
        }
    };

    private final DetailBindings detailBindings = new DetailBindings() {
        @Override
        public EventData line(DetailBlock b, DetailLine l) {
            return l.selectId() == null ? null : EventData.of("Action", LINE).append("QuestId", l.selectId());
        }

        @Override
        public EventData toggle(DetailToggle t) {
            // Bound once in build (bindActionsOnce), never by a repaint.
            return null;
        }
    };

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
        try {
            paint(ref, cmd, events, store);
        } catch (Throwable t) {
            // A build that throws leaves the client with no page at all; log it and send what was painted.
            SafeLog.warn("[progression] the npc quest page's build failed", t);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, NpcQuestPagePlan.CLOSE,
                EventData.of("Action", CLOSE));
        bindTabs(events);
        // Bound ONCE per build with no quest id: a press is dispatched on the live state of whatever the page shows,
        // so a partial update can swap the page without a binding it cannot add.
        DetailPainter.bindActionsOnce(events, NpcQuestPagePlan.PAGE,
                slot -> EventData.of("Action", slotAction(slot)), EventData.of("Action", TOGGLE));

        this.answersTo = deps.answerSetOrOwn(npcId);
        cmd.set(NpcQuestPagePlan.HEADER + ".TextSpans", headerText(store, ref));

        QuestEngine engine = ProgressionRuntime.quests();
        // The build argument is the page's ANCHOR, which at a character is that character's own
        // entity, so the player is resolved from the reference this page was built with.
        Subject subject = ProgressionRuntime.subjects().questSubject(store, playerEntityRef(ref));
        if (subject == null) {
            // Nothing can be read for this player, so say so once rather than painting an empty list that reads as
            // "this character has nothing", which is a different sentence.
            this.model = null;
            this.index = null;
            cmd.set(NpcQuestPagePlan.COUNT + ".TextSpans", text("npcquests.count", 0));
            paintTabs(cmd);
            showEmpty(cmd, events, text("npcquests.empty.unavailable"));
            return;
        }

        // Both engines document self-heal as "whenever a surface opens", and it matters most at a character: a
        // finished daily whose cooldown has elapsed must read as re-acceptable HERE rather than staying stuck on
        // "completed", and a standing-value step is settled in the same pass.
        selfHeal(engine, subject);

        CharacterQuestListing listing = listing(subject, engine);
        if (!routed) {
            routed = true;
            if (highlightQuestId != null) {
                // A routed quest that is not on this character's list still has to be reachable, so the page opens
                // on the list that does hold it rather than on an empty panel.
                this.activeTab = NpcQuestPagePlan.tab(activeTab, highlightQuestId,
                        NpcQuestPagePlan.quests(TAB_HERE, listing, engine, subject),
                        NpcQuestPagePlan.quests(TAB_MINE, listing, engine, subject));
            }
        }
        paintTabs(cmd);
        paintList(cmd, events, engine, subject, listing, false);
    }

    /**
     * Paint the count, the list and the page from the engine's state now: the list through the kit's ledger, the
     * selected quest through the kit's page, or the empty state. Used by the build and by the partial update after
     * an action (every element it binds is appended in the same update).
     *
     * @param partial true in a partial update, where the list's previous rows are cleared by the painter and the
     *                empty state's leftovers are hidden explicitly
     */
    private void paintList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull CharacterQuestListing listing, boolean partial) {
        List<Quest> quests = NpcQuestPagePlan.quests(activeTab, listing, engine, subject);
        QuestReader reader = reader(engine, subject);
        CharacterQuestListing here = NpcQuestPagePlan.place(npcId, listing);
        Map<String, Quest> byId = new LinkedHashMap<>();
        for (Quest quest : quests) {
            byId.put(quest.id(), quest);
        }
        LedgerModel next = NpcQuestPagePlan.model(
                NpcQuestPagePlan.entries(quests, listing::sectionOf, highlightQuestId),
                id -> {
                    Quest quest = byId.get(id);
                    return quest == null ? null : NpcQuestPagePlan.row(reader, quest, here);
                },
                this::sectionLabel, caps);
        this.model = next;
        this.selectedQuestId = NpcQuestPagePlan.select(next, routeSelects ? highlightQuestId : null,
                selectedQuestId);
        this.routeSelects = false;
        cmd.set(NpcQuestPagePlan.COUNT + ".TextSpans", text("npcquests.count", NpcQuestPagePlan.rowCount(next)));

        if (next.isEmpty()) {
            this.index = null;
            if (partial) {
                cmd.clear(NpcQuestPagePlan.LIST);
            }
            showEmpty(cmd, events, text(TAB_MINE.equals(activeTab)
                    ? "npcquests.empty.mine" : "npcquests.empty.here"));
            return;
        }
        this.openSections = NpcQuestPagePlan.openSections(next, openSections, selectedQuestId);
        this.index = LedgerPainter.paint(cmd, events, NpcQuestPagePlan.LIST, next, openSections, selectedQuestId,
                ledgerBindings, RowSize.STANDARD, playerRef);
        Quest selected = selectedQuestId == null ? null : byId.get(selectedQuestId);
        if (selected != null) {
            paintPage(cmd, events, reader, listing, selected);
        } else {
            showEmpty(cmd, events, text("npcquests.empty.here"));
        }
    }

    /** The selected quest's page: the book's page, with this character's buttons. */
    private void paintPage(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull QuestReader reader, @Nonnull CharacterQuestListing listing, @Nonnull Quest quest) {
        cmd.set(NpcQuestPagePlan.PAGE_EMPTY + ".Visible", false);
        cmd.set(NpcQuestPagePlan.PAGE + ".Visible", true);
        DetailPainter.paint(cmd, events, NpcQuestPagePlan.PAGE,
                NpcQuestPagePlan.page(reader, quest, NpcQuestPagePlan.place(npcId, listing)), detailBindings,
                playerRef);
    }

    /**
     * The empty state in the page's place, the segments left on screen and bound: a whole-page empty state would
     * hide the only route back to the other list.
     */
    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Message line) {
        cmd.set(NpcQuestPagePlan.PAGE + ".Visible", false);
        cmd.set(NpcQuestPagePlan.PAGE_EMPTY + ".Visible", true);
        EmptyStatePainter.paint(cmd, events, NpcQuestPagePlan.PAGE_EMPTY,
                new EmptyState(Picture.item(ObjectiveBookMenu.QUESTS_ICON), line, null, null), null);
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
            deps.theme().appendThemed(cmd, NpcQuestPagePlan.DOCUMENT, NpcQuestPagePlan.FRAME_PANEL);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[progression] a page theme failed, so the npc quest page renders plain: "
                    + t.getMessage());
        }
        cmd.append(NpcQuestPagePlan.DOCUMENT);
    }

    private void selfHeal(@Nonnull QuestEngine engine, @Nonnull Subject subject) {
        try {
            engine.selfHeal(subject);
        } catch (Throwable t) {
            SafeLog.warn("[progression] npc quest page self-heal failed", t);
        }
    }

    /**
     * The character's name, else its raw id, else a plain title for a list with nobody in front of
     * it.
     *
     * <p>The consumer's naming seam answers first (it exists to override), then the LIVE entity
     * this page was opened on: at a press-F the build anchor is the character itself, and the key
     * its built role carries is byte-for-byte what the nameplate over its head renders - which
     * also covers a role shape the static asset walk cannot resolve. The raw id is the last
     * resort, not an answer.
     */
    @Nonnull
    private Message headerText(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (npcId == null) {
            return text("npcquests.title.none");
        }
        Message name = deps.nameOrNull(npcId);
        if (name != null) {
            return name;
        }
        name = NpcNames.nameFor(npcId, ref, store);
        return name != null ? name : Msg.raw(npcId);
    }

    /**
     * What this character is to this player's quests, read the one way every surface reads it: which quests belong
     * on the list, which section each sits in, and the place-aware facts behind both. The indicator floating over
     * the character's head reads the same class, so the two cannot disagree.
     */
    @Nonnull
    private CharacterQuestListing listing(@Nonnull Subject subject, @Nonnull QuestEngine engine) {
        return new CharacterQuestListing(engine, subject, answersTo);
    }

    /** How a quest reads here: as the book reads it, with this page's reward reading and character names. */
    @Nonnull
    private QuestReader reader(@Nonnull QuestEngine engine, @Nonnull Subject subject) {
        return QuestReader.of(engine, subject, deps.presentation(), playerRef.getUuid(), System.currentTimeMillis());
    }

    // ==================== the segments ====================

    private void paintTabs(@Nonnull UICommandBuilder cmd) {
        boolean mine = TAB_MINE.equals(activeTab);
        SegmentPainter.set(cmd, NpcQuestPagePlan.SEGMENT_HERE, text("npcquests.tab.here"), !mine, playerRef);
        SegmentPainter.set(cmd, NpcQuestPagePlan.SEGMENT_MINE, text("npcquests.tab.mine"), mine, playerRef);
    }

    private static void bindTabs(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, NpcQuestPagePlan.SEGMENT_HERE,
                EventData.of("Action", TAB).append("Tab", TAB_HERE), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, NpcQuestPagePlan.SEGMENT_MINE,
                EventData.of("Action", TAB).append("Tab", TAB_MINE), false);
    }

    @Nonnull
    private static String slotAction(@Nonnull ActionSlot slot) {
        return switch (slot) {
            case PRIMARY -> PRIMARY;
            case SECONDARY -> SECONDARY;
            case DANGER -> DANGER;
        };
    }

    @Nullable
    private static ActionSlot slotOf(@Nonnull String action) {
        return switch (action) {
            case PRIMARY -> ActionSlot.PRIMARY;
            case SECONDARY -> ActionSlot.SECONDARY;
            case DANGER -> ActionSlot.DANGER;
            default -> null;
        };
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull NpcQuestEventData data) {
        Player player;
        try {
            player = store.getComponent(ref, Player.getComponentType());
        } catch (Throwable t) {
            player = null;
        }
        if (player == null) {
            answer();
            return;
        }
        try {
            handle(ref, store, player, data);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the npc quest page could not answer an event", t);
            answer();
        }
    }

    private void handle(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull NpcQuestEventData data) {
        String action = data.action;
        if (action == null || CLOSE.equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        switch (action) {
            case TAB -> {
                this.activeTab = TAB_MINE.equals(data.tab) ? TAB_MINE : TAB_HERE;
                // The new list opens on its own first row, unless the routed quest is on it.
                this.selectedQuestId = null;
                this.routeSelects = true;
                this.openSections = new LinkedHashSet<>();
                this.caps = new HashMap<>();
                reopen(ref, store, player);
                return;
            }
            case SELECT, LINE -> {
                selectQuest(ref, store, player, data.questId);
                return;
            }
            case SECTION -> {
                toggleSection(ref, store, player, data.section);
                return;
            }
            case MORE -> {
                if (data.section != null) {
                    this.caps = NpcQuestPagePlan.showMore(caps, data.section);
                }
                refresh(ref, store, player);
                return;
            }
            default -> {
                // A press on the page: its three buttons and its header toggle.
            }
        }

        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ProgressionRuntime.subjects().questSubject(store, playerEntityRef(ref));
        Quest quest = selectedQuestId == null ? null : engine.quest(selectedQuestId);
        if (subject == null || quest == null) {
            reopen(ref, store, player);
            return;
        }
        CharacterQuestListing listing = listing(subject, engine);
        String verb = verbFor(action, quest, reader(engine, subject), listing);
        if (verb == null) {
            refresh(ref, store, player);
            return;
        }
        switch (verb) {
            // The hand-in and the collect own their own response: a settled quest may hand the screen over to
            // whatever comes next instead of returning to this page.
            case QuestActions.HAND_IN -> turnIn(ref, store, player, subject, engine, listing, quest);
            case QuestActions.COLLECT -> claim(ref, store, player, subject, engine, listing, quest);
            case QuestActions.ACCEPT -> {
                accept(subject, engine, quest);
                refresh(ref, store, player);
            }
            case QuestActions.ABANDON -> {
                abandon(subject, engine, quest);
                refresh(ref, store, player);
            }
            case QuestActions.TRACK -> {
                track(subject, engine, quest);
                refresh(ref, store, player);
            }
            default -> refresh(ref, store, player);
        }
    }

    /**
     * The verb a press means on the quest's state NOW: a slot through {@link QuestActions#dispatch} at this character
     * (or by the book's rules with nobody in front of the player), the header toggle as Track when the page offers
     * it. Null for a press that means nothing any more (a stale screen).
     */
    @Nullable
    private String verbFor(@Nonnull String action, @Nonnull Quest quest, @Nonnull QuestReader reader,
            @Nonnull CharacterQuestListing listing) {
        CharacterQuestListing here = NpcQuestPagePlan.place(npcId, listing);
        if (TOGGLE.equals(action)) {
            return NpcQuestPagePlan.page(reader, quest, here).toggle() != null ? QuestActions.TRACK : null;
        }
        ActionSlot slot = slotOf(action);
        return slot == null ? null : QuestActions.dispatch(slot, quest, reader, here);
    }

    /**
     * Move the selection and repaint the page in place, so the list keeps its scroll. A quest whose row the last
     * build did not draw (in a closed section, or not on the list) reopens instead, which opens its section.
     */
    private void selectQuest(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String questId) {
        QuestEngine engine = ProgressionRuntime.quests();
        Quest quest = questId == null ? null : engine.quest(questId);
        LedgerModel shown = this.model;
        if (quest == null || shown == null || !shown.contains(questId)) {
            // Nothing on this list to open: a stale click, or a line naming a quest this list does not hold.
            answer();
            return;
        }
        Subject subject = ProgressionRuntime.subjects().questSubject(store, playerEntityRef(ref));
        LedgerIndex painted = this.index;
        if (subject == null || painted == null || painted.rowSelector(questId) == null) {
            this.selectedQuestId = questId;
            reopen(ref, store, player);
            return;
        }
        String previous = this.selectedQuestId;
        this.selectedQuestId = questId;
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        LedgerPainter.select(cmd, painted, previous, questId, playerRef);
        paintPage(cmd, events, reader(engine, subject), listing(subject, engine), quest);
        this.sendUpdate(cmd, events, false);
    }

    /** Fold or unfold a section, remembering the player's answer for every repaint after it. */
    private void toggleSection(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String sectionId) {
        LedgerModel shown = this.model;
        LedgerIndex painted = this.index;
        LedgerSection section = shown == null || sectionId == null ? null : sectionOf(shown, sectionId);
        if (section == null || painted == null) {
            answer();
            return;
        }
        boolean open = !painted.isOpen(sectionId);
        this.openSections = LedgerPainter.withSection(openSections, sectionId, open);
        if (!LIST_IN_PLACE) {
            reopen(ref, store, player);
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        if (open) {
            LedgerPainter.openSection(cmd, events, painted, section, ledgerBindings);
        } else {
            LedgerPainter.closeSection(cmd, painted, sectionId);
        }
        this.sendUpdate(cmd, events, false);
    }

    /**
     * Repaint the count, the list and the page from the engine's state now, in place, so the scroll survives and a
     * quest that moved section is drawn under its new head; reopen when the list cannot be repainted in place.
     */
    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        QuestEngine engine = ProgressionRuntime.quests();
        Subject subject = ProgressionRuntime.subjects().questSubject(store, playerEntityRef(ref));
        if (!LIST_IN_PLACE || subject == null) {
            reopen(ref, store, player);
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        this.answersTo = deps.answerSetOrOwn(npcId);
        paintList(cmd, events, engine, subject, listing(subject, engine), true);
        this.sendUpdate(cmd, events, false);
    }

    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        player.getPageManager().openCustomPage(ref, store, this);
    }

    /** An empty update, for an event that changed nothing: the client always hears back. */
    private void answer() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    // ==================== the verbs ====================

    /**
     * Take the quest on, RECORDING that it was taken here.
     *
     * <p>The site is what makes a quest that must be settled where it was taken work at all, and what puts it on
     * this character's list while it is being carried. Passing it always is deliberate: the content decides whether
     * it matters, and a surface that decided for it would have to know.
     */
    private void accept(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        ProgressionCallScope scope = ProgressionRuntime.questScope();
        boolean ok = Boolean.TRUE.equals(scope.around(subject, s ->
                Boolean.valueOf(engine.canAccept(s, quest).allowed() && engine.accept(s, quest, npcId))));
        showToast(ok ? ToastKind.SUCCESS : ToastKind.WARNING,
                text(ok ? "book.toast.accepted" : "book.toast.accept_failed"));
    }

    /**
     * Collect a finished quest AT the id this character answered under, and answer the player one way or another on
     * every path. The consumer's pre-check (the book's, so a full bag refuses here as it does there) is asked first;
     * the engine re-checks the site itself, so a quest belonging somewhere else refuses here even if a stale screen
     * offered it.
     *
     * <p>Collecting is the moment a quest's closing conversation is FOR: a quest that names one has it played here,
     * through the same hand-off the hand-in uses, and only where there is somebody in front of the player to speak
     * it.
     */
    private void claim(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull CharacterQuestListing listing,
            @Nonnull Quest quest) {
        Message refusal = preCheck(quest, store, ref, player);
        if (refusal != null) {
            showToast(ToastKind.ERROR, refusal);
            refresh(ref, store, player);
            return;
        }
        String site = listing.collectionSite(quest);
        RewardGrants.GrantOutcome paid = ProgressionRuntime.questScope()
                .around(subject, s -> engine.tryClaim(s, quest, site));
        if (paid == null) {
            showToast(ToastKind.WARNING, text("book.toast.claim_failed"));
            refresh(ref, store, player);
            return;
        }
        // ORDER IS LOAD-BEARING, exactly as on the hand-in below: the toast goes up FIRST, because whatever the
        // hand-off opens repaints the shared per-player toast state. The toast NAMES what was collected, one row per
        // thing actually handed over (the claim's RECEIPT), through the same chip reading the page previewed it with.
        showToast(ClaimToasts.rewardToast(text("book.toast.claimed"), paid.receipt(),
                deps.rewardChips(), dropped -> text("book.more", dropped)));
        if (!handOff(quest, store, ref, player)) {
            refresh(ref, store, player);
        }
    }

    /** The consumer's refusal of a Collect before the engine is asked; guarded: a seam that throws refuses nothing. */
    @Nullable
    private Message preCheck(@Nonnull Quest quest, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        try {
            return deps.presentation().claimPreCheck(quest, store, ref, player);
        } catch (Throwable t) {
            return null;
        }
    }

    private void abandon(@Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull Quest quest) {
        boolean ok = Boolean.TRUE.equals(ProgressionRuntime.questScope()
                .around(subject, s -> Boolean.valueOf(engine.abandon(s, quest.id()))));
        showToast(ok ? ToastKind.INFO : ToastKind.WARNING,
                text(ok ? "npcquests.toast.abandoned" : "book.toast.abandon_failed"));
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
     * Hand in every outstanding step this character is owed, and answer the player one way or another on every path.
     *
     * <p>The button is offered whenever a step is outstanding here rather than only when the player is carrying
     * everything, so a shortfall is answered with what is still owed instead of a control that is silently not
     * there.
     */
    private void turnIn(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull Subject subject, @Nonnull QuestEngine engine, @Nonnull CharacterQuestListing listing,
            @Nonnull Quest quest) {
        CharacterQuestListing.TurnIn turnIn = listing.turnInHere(quest);
        if (turnIn == null) {
            refresh(ref, store, player);
            return;
        }
        // Handed in AT the id this character answered under: the hand-in that finishes a quest at its own collection
        // site pays out there and then, while the same hand-in from nowhere parks it. EVERY outstanding step this
        // character is owed, not just the first: three deliveries to one person is one errand to the player.
        QuestEngine.TurnInOutcome handed = ProgressionRuntime.questScope().around(subject,
                s -> engine.tryAllTurnIns(s, quest, turnIn.atId()));
        if (handed == null || !handed.creditedAny()) {
            ObjectiveProgressState state = engine.progressOf(subject, quest.id(), turnIn.step().id());
            showToast(ToastKind.WARNING, state == null
                    ? text("book.toast.turn_in_failed")
                    : text("npcquests.toast.turn_in_short", state.current(), state.required()));
            refresh(ref, store, player);
            return;
        }
        if (engine.status(subject, quest) == QuestStatus.ACTIVE) {
            showToast(ToastKind.SUCCESS, text("book.toast.turned_in"));
            refresh(ref, store, player);
            return;
        }
        // What this press PAID: the settle inside the hand-in when the quest paid out here, else the collect right
        // behind it. A hand-in made HERE also COLLECTS here, in the SAME call scope; a refusal (no room, or the quest
        // wants collecting somewhere else) simply leaves it parked with nothing paid.
        RewardGrants.GrantOutcome paid = handed.paid();
        if (engine.status(subject, quest) == QuestStatus.COMPLETED_UNCLAIMED) {
            paid = ProgressionRuntime.questScope().around(subject,
                    s -> engine.tryClaim(s, quest, turnIn.atId()));
        }
        // ORDER IS LOAD-BEARING: the toast goes up FIRST, because whatever the hand-off opens repaints the shared
        // per-player toast state. False from the hand-off means nothing was painted, so this page still owes the
        // player a response.
        showToast(handInToast(quest, paid));
        if (!handOff(quest, store, ref, player)) {
            refresh(ref, store, player);
        }
    }

    /**
     * The toast for a quest this hand-in finished: the gold "quest complete" line listing what was actually handed
     * over when it paid out here, the plain "Handed in." line when it only parked. The split is the deps' to apply.
     */
    @Nonnull
    private ToastSpec handInToast(@Nonnull Quest quest, @Nullable RewardGrants.GrantOutcome paid) {
        return deps.handInToast(quest, paid,
                text("book.toast.quest_complete", ProgressionTexts.titleOrUntitled(quest.id())),
                text("book.toast.turned_in"),
                dropped -> text("book.more", dropped));
    }

    private boolean handOff(@Nonnull Quest quest, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        try {
            return deps.completion().handOff(quest.id(), npcId, store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[progression] a quest completion hand-off failed", t);
            return false;
        }
    }

    // ==================== text ====================

    @Nonnull
    private Message sectionLabel(@Nonnull Section section) {
        return switch (section) {
            case READY -> text("npcquests.section.ready");
            case TURN_IN -> text("npcquests.section.turn_in");
            case ACTIVE -> text("npcquests.section.active");
            case AVAILABLE -> text("npcquests.section.available");
            case PARKED -> text("npcquests.section.parked");
            case COOLDOWN -> text("npcquests.section.cooldown");
            case LOCKED -> text("npcquests.section.locked");
            case DONE -> text("npcquests.section.done");
        };
    }

    @Nonnull
    private Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    @Nullable
    private static LedgerSection sectionOf(@Nonnull LedgerModel model, @Nonnull String sectionId) {
        for (LedgerSection section : model.sections()) {
            if (section.id().equals(sectionId)) {
                return section;
            }
        }
        return null;
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
