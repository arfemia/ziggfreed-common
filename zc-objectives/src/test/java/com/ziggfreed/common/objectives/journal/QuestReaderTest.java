package com.ziggfreed.common.objectives.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.BookVerbs;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * The quest reader over a real engine with an in-memory store: every state a quest can be in, read into its section,
 * its row and its page, against the cases that change what a page may offer (board-managed, giver-bound, a hand-in,
 * a quest collected only at its site, a cooldown, ordered and hidden steps) and the journal's cap.
 */
class QuestReaderTest {

    private static final String J = "ziggfreedcommon.journal.";
    private static final long HOUR = 3_600_000L;
    private static final String GUIDE = "guide";
    private static final String STRANGER = "stranger";

    private long now = 1_000_000_000L;
    private QuestEngine engine;
    private Subject player;
    private FakePresentation presentation;

    /** A consumer the test controls: which quests a board manages, its pills, hint and requirement line. */
    static final class FakePresentation implements QuestPresentation {

        final Set<String> managed = new HashSet<>();
        /** Names by character id, asked ahead of {@link #npcName}, which answers for any id. */
        final Map<String, Message> names = new HashMap<>();
        @Nullable Message requirement;
        @Nullable Message npcName;

        @Override
        public boolean managed(@Nonnull Quest q) {
            return managed.contains(q.id());
        }

        @Nonnull
        @Override
        public List<Pill> pills(@Nonnull Quest q) {
            return managed(q) ? List.of(new Pill(Msg.raw("Bounty"), Tone.NEUTRAL, "#6f5a8e")) : List.of();
        }

        @Nullable
        @Override
        public Message acceptHint(@Nonnull Quest q) {
            return managed(q) ? Msg.raw("Take it at the board") : null;
        }

        @Nullable
        @Override
        public Message requirementLine(@Nonnull Quest q) {
            return requirement;
        }

        @Nonnull
        @Override
        public Message tagLabel(@Nonnull String tag) {
            return Msg.raw("Tag " + tag);
        }

        @Nullable
        @Override
        public Message claimPreCheck(@Nonnull Quest q, @Nonnull Store<EntityStore> s, @Nonnull Ref<EntityStore> r,
                @Nonnull Player p) {
            return null;
        }

        @Nullable
        @Override
        public Message npcName(@Nullable String npcId) {
            Message named = npcId == null ? null : names.get(npcId);
            return named != null ? named : npcName;
        }
    }

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true)
                .maxActive(10)
                .maxTracked(3)
                .clock(() -> now)
                .gates(LOCKED_GATE)
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
        presentation = new FakePresentation();
    }

    private QuestReader reader() {
        return QuestReader.of(engine, player, presentation, player.id(), now);
    }

    private static ObjectiveDef mine(String id, int order) {
        return ObjectiveDef.builder(id, "BREAK_BLOCK").target("Copper_Ore").amount(3).order(order).build();
    }

    private static Quest plain(String id) {
        return Quest.builder(id).category("main").objective(mine("mine", 0)).build();
    }

    private static Quest handIn(String id) {
        return Quest.builder(id).category("main")
                .objective(ObjectiveDef.builder("give", "TURN_IN").target("Copper_Ore").amount(3).build())
                .build();
    }

    private static Quest giverBound(String id) {
        return Quest.builder(id).category("main").npcViewId("Mmo_Mastery_Trainer").objective(mine("mine", 0)).build();
    }

    /** A gate refusing every quest whose id starts {@code q_locked}, the way a prerequisite would. */
    private static final QuestGates LOCKED_GATE = new QuestGates() {
        @Override
        public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest, @Nonnull List<String> reasons) {
            if (quest.id().startsWith("q_locked")) {
                reasons.add(QuestGates.REASON_PREREQUISITES);
                return false;
            }
            return true;
        }
    };

    /** A quest the test's gate refuses ({@code id} must start {@code q_locked}). */
    private static Quest locked(String id, String after) {
        assertTrue(id.startsWith("q_locked"), "the test gate refuses q_locked* only");
        return Quest.builder(id).category("misc").objective(mine("mine", 0)).build();
    }

    private static Quest daily(String id) {
        return Quest.builder(id).category("main").repeat(Quest.Repeat.every(HOUR)).objective(mine("mine", 0)).build();
    }

    private static Quest ordered(String id, boolean hideLocked) {
        return Quest.builder(id).category("main").hideLockedSteps(hideLocked)
                .objective(mine("a", 1)).objective(mine("b", 1)).objective(mine("c", 2)).build();
    }

    private void set(Quest... quests) {
        engine.setQuests(List.of(quests));
    }

    /** A quest collected only at {@code site}. */
    private static Quest sited(String id, QuestTurnInSite site) {
        return Quest.builder(id).category("main").turnInAt(site).objective(mine("mine", 0)).build();
    }

    /** {@code quest}, the only one in the catalogue, taken at {@code takenAt} and finished, its rewards waiting. */
    private Quest finishedAt(Quest quest, String takenAt) {
        set(quest);
        assertTrue(engine.accept(player, quest, takenAt));
        engine.markUnclaimed(player, quest);
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, quest));
        return quest;
    }

    private static String id(@Nullable Message message) {
        assertNotNull(message, "a message is there");
        return message.getMessageId();
    }

    /** The name nested as a message's first param. */
    private static String named(@Nullable Message message) {
        assertNotNull(message, "a message is there");
        FormattedMessage formatted = message.getFormattedMessage();
        FormattedMessage name = formatted.messageParams == null ? null : formatted.messageParams.get("0");
        assertNotNull(name, "the name rides as a nested message");
        return name.rawText;
    }

    // ==================== sections ====================

    @Test
    void everyStateHasItsSection() {
        Quest available = plain("q_available");
        Quest first = plain("q_first");
        Quest lockedQuest = locked("q_locked", "q_first");
        Quest active = plain("q_active");
        Quest ready = plain("q_ready");
        Quest done = plain("q_done");
        Quest waiting = daily("q_waiting");
        set(available, first, lockedQuest, active, ready, done, waiting);
        assertTrue(engine.accept(player, active));
        assertTrue(engine.accept(player, ready));
        engine.markUnclaimed(player, ready);
        assertTrue(engine.accept(player, done));
        engine.markCompleted(player, done);
        assertTrue(engine.accept(player, waiting));
        engine.markCompleted(player, waiting);
        assertEquals(QuestStatus.ON_COOLDOWN, engine.status(player, waiting));

        QuestReader r = reader();
        assertEquals(QuestSection.AVAILABLE, r.sectionOf(available));
        assertEquals(QuestSection.NOT_YET, r.sectionOf(lockedQuest), "a gate refuses it");
        assertEquals(QuestSection.IN_PROGRESS, r.sectionOf(active));
        assertEquals(QuestSection.READY, r.sectionOf(ready));
        assertEquals(QuestSection.COMPLETED, r.sectionOf(done));
        assertEquals(QuestSection.WAITING, r.sectionOf(waiting));
    }

    @Test
    void aRowSpeaksInItsSectionsToneAndWord() {
        Quest available = plain("q_available");
        Quest active = plain("q_active");
        Quest ready = plain("q_ready");
        Quest done = plain("q_done");
        Quest lockedQuest = locked("q_locked", "q_nothing");
        set(available, active, ready, done, lockedQuest);
        assertTrue(engine.accept(player, active));
        assertTrue(engine.accept(player, ready));
        engine.markUnclaimed(player, ready);
        assertTrue(engine.accept(player, done));
        engine.markCompleted(player, done);

        QuestReader r = reader();
        LedgerRow row = r.row(active);
        assertEquals("q_active", row.id());
        assertEquals(Tone.ACTIVE, row.tone());
        assertEquals(J + "state.progress", id(row.state()));
        assertNotNull(row.value(), "a carried quest shows its step tally");
        assertNotNull(row.progress(), "an in-progress quest shows its bar");
        assertEquals(0, row.progress().current());
        assertFalse(row.faint());

        LedgerRow readyRow = r.row(ready);
        assertEquals(Tone.COLLECT, readyRow.tone());
        assertEquals(J + "state.collect", id(readyRow.state()));
        assertNotNull(readyRow.value(), "a quest waiting to be collected still shows its tally");
        assertNull(readyRow.progress(), "only an in-progress row carries a bar");

        assertEquals(Tone.AVAILABLE, r.row(available).tone());
        assertEquals(J + "state.available", id(r.row(available).state()));
        assertNull(r.row(available).value(), "a quest not taken has no tally");
        assertEquals(Tone.BLOCKED, r.row(lockedQuest).tone());
        assertEquals(J + "state.locked", id(r.row(lockedQuest).state()));

        LedgerRow doneRow = r.row(done);
        assertEquals(Tone.DONE, doneRow.tone());
        assertEquals(J + "state.done", id(doneRow.state()));
        assertTrue(doneRow.faint(), "a finished row reads faint");

        assertNotNull(r.row(active).meta(), "the standard row has a meta line");
        assertNull(r.compactRow(active).meta(), "the compact row has none");
    }

    @Test
    void aWaitingRowSaysWhenItComesBack() {
        Quest waiting = daily("q_waiting");
        set(waiting);
        assertTrue(engine.accept(player, waiting));
        engine.markCompleted(player, waiting);

        LedgerRow row = reader().row(waiting);
        assertEquals(Tone.WAITING, row.tone());
        assertEquals(J + "state.back_in", id(row.state()));
        assertNotNull(row.state().getFormattedMessage().messageParams.get("0"), "the wait rides as a nested line");

        DetailView page = reader().page(waiting);
        assertNull(page.action(ActionSlot.PRIMARY), "nothing to press while it waits");
        assertEquals(J + "state.back_in", id(page.hint()), "the hint holds the wait");
    }

    @Test
    void aTrackedQuestCarriesTheMark() {
        Quest active = plain("q_active");
        set(active);
        assertTrue(engine.accept(player, active));
        assertEquals(Mark.NONE, reader().row(active).mark());
        assertTrue(engine.track(player, active.id()));
        assertEquals(Mark.TRACKED, reader().row(active).mark());
        assertTrue(reader().tracked(active));
    }

    @Test
    void aStartedQuestTheCatalogueStopsOfferingLeavesTheJournalAndTheTrackerAndComesBackWithItsProgress() {
        AtomicBoolean running = new AtomicBoolean(true);
        Quest seasonal = Quest.builder("q_seasonal").category("main").objective(mine("mine", 0))
                .available(running::get).build();
        set(seasonal);
        assertTrue(engine.accept(player, seasonal));
        assertTrue(engine.track(player, "q_seasonal"));
        engine.dispatch(player, "BREAK_BLOCK", "Copper_Ore", null, 1);
        assertTrue(reader().listed().stream().anyMatch(q -> q.id().equals("q_seasonal")), "listed while it runs");

        running.set(false);
        assertFalse(reader().listed().stream().anyMatch(q -> q.id().equals("q_seasonal")),
                "off-season a started quest leaves the journal");
        assertTrue(engine.trackedActive(player).isEmpty(), "and the tracked-quest HUD");
        assertEquals(QuestStatus.ACTIVE, engine.status(player, seasonal), "it is still carried");
        assertEquals(1, engine.progressOf(player, "q_seasonal", "mine").current(), "with its progress");

        running.set(true);
        assertTrue(reader().listed().stream().anyMatch(q -> q.id().equals("q_seasonal")), "back in the journal");
        assertEquals(List.of("q_seasonal"), engine.trackedActive(player).stream().map(Quest::id).toList(),
                "and on the tracker, its pin kept");
        assertEquals(1, engine.progressOf(player, "q_seasonal", "mine").current(), "where the player left it");
    }

    // ==================== giver-bound (GiverBoundQuestTest, ported) ====================

    @Test
    void aQuestWithAGiverIsGiverBoundAndAPlainOneIsNot() {
        assertTrue(BookVerbs.giverBound(
                Quest.builder("q_giver").npcViewId("Mmo_Mastery_Trainer").build()));
        assertFalse(BookVerbs.giverBound(Quest.builder("q_plain").build()));
    }

    /**
     * The engine must keep accepting giver-bound quests: the refusal lives in the book, never in
     * {@code QuestEngine.canAccept}, or the NPC quest page could no longer hand them out.
     */
    @Test
    void theEngineItselfStillAcceptsAGiverBoundQuest() {
        Quest quest = Quest.builder("q_giver").npcViewId("Mmo_Mastery_Trainer").build();
        QuestEngine engine = QuestEngine.builder().nativeEvents(false)
                .warn(message -> { }).build();
        engine.setQuests(List.of(quest));
        Subject player = Subject.of(UUID.randomUUID(), "tester");

        assertTrue(engine.canAccept(player, quest).allowed(),
                "the engine's gate stays open for the NPC quest page");
        assertTrue(engine.accept(player, quest),
                "accepting at the giver keeps working");
    }

    @Test
    void aGiverBoundQuestIsAvailableButNeverAcceptedFromTheBook() {
        Quest quest = giverBound("q_giver");
        set(quest);
        presentation.npcName = Msg.raw("Old Jack");

        QuestReader r = reader();
        assertEquals(QuestSection.AVAILABLE, r.sectionOf(quest), "it can be taken, at its giver");
        DetailView page = r.page(quest);
        assertNull(page.action(ActionSlot.PRIMARY), "no Accept in the book");
        assertEquals(J + "hint.talk_to", id(page.hint()));
        assertEquals("Old Jack", page.hint().getFormattedMessage().messageParams.get("0").rawText,
                "the hint names the giver");

        presentation.npcName = null;
        assertEquals(J + "hint.talk_to_plain", id(reader().page(quest).hint()), "no name, the plain hint");
    }

    // ==================== board-managed ====================

    @Test
    void aBoardManagedQuestListsOnlyWhileCarriedAndWearsTheBoardsWords() {
        Quest board = plain("q_board");
        Quest open = plain("q_open");
        set(board, open);
        presentation.managed.add(board.id());

        assertEquals(List.of(open), reader().listed(), "offering a board's quest is the board's job");

        assertTrue(engine.accept(player, board));
        QuestReader r = reader();
        assertTrue(r.listed().contains(board), "carried, it lists");
        DetailView page = r.page(board);
        assertEquals(List.of("Bounty"), page.badges().stream().map(p -> p.label().getRawText()).toList(),
                "the board's pills, not the plumbing tags");
        assertEquals("Take it at the board", page.hint().getRawText());
        assertNull(page.action(ActionSlot.PRIMARY), "the board takes the hand-in");
        assertNotNull(page.action(ActionSlot.DANGER), "but it can be dropped from the log");

        engine.markCompleted(player, board);
        assertFalse(reader().listed().contains(board), "finished, the board shows it, not the log");
    }

    // ==================== hand-in and collect ====================

    @Test
    void aStepThatCanBeHandedInOffersHandIn() {
        Quest quest = handIn("q_hand");
        set(quest);
        assertTrue(engine.accept(player, quest));

        DetailView page = reader().page(quest);
        assertNotNull(page.action(ActionSlot.PRIMARY));
        assertEquals(QuestActions.HAND_IN, page.action(ActionSlot.PRIMARY).actionId());
        assertEquals(QuestActions.ABANDON, page.action(ActionSlot.DANGER).actionId());
    }

    @Test
    void aQuestCollectedAtItsSiteIsNotCollectedFromTheBook() {
        Quest quest = Quest.builder("q_site").category("main").turnInAt(QuestTurnInSite.character(GUIDE))
                .objective(mine("mine", 0)).build();
        set(quest);
        assertTrue(engine.accept(player, quest, GUIDE));
        engine.markUnclaimed(player, quest);

        DetailView book = reader().page(quest);
        assertNull(book.action(ActionSlot.PRIMARY), "the engine refuses a placeless payout, so no Collect");
        assertEquals(J + "hint.collect_at_site", id(book.hint()));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        DetailView there = reader().page(quest, atGuide);
        assertEquals(QuestActions.COLLECT, there.action(ActionSlot.PRIMARY).actionId(), "collected at its site");
        assertNull(there.hint());
    }

    @Test
    void aReadyQuestCollectsInGold() {
        Quest quest = plain("q_ready");
        set(quest);
        assertTrue(engine.accept(player, quest));
        engine.markUnclaimed(player, quest);

        DetailView page = reader().page(quest);
        assertEquals(QuestActions.COLLECT, page.action(ActionSlot.PRIMARY).actionId());
        assertEquals(ActionLook.COLLECT, page.action(ActionSlot.PRIMARY).look());
        assertNull(page.action(ActionSlot.DANGER), "a finished quest cannot be dropped");
    }

    // ==================== collected elsewhere ====================

    /**
     * At a character, a finished quest whose rewards belong to another character (the NPC page's "Collect elsewhere"
     * section) reads Elsewhere, offers no Collect, and its hint names the character that collects it.
     */
    @Test
    void atACharacterAQuestParkedForAnotherReadsElsewhereAndNamesWhoCollectsIt() {
        Quest quest = finishedAt(sited("q_site", QuestTurnInSite.character(GUIDE)), STRANGER);
        presentation.names.put(GUIDE, Msg.raw("Guide Maren"));
        presentation.names.put(STRANGER, Msg.raw("A Stranger"));

        CharacterQuestListing atStranger = new CharacterQuestListing(engine, player, Set.of(STRANGER));
        assertEquals(NpcQuestSections.Section.PARKED, atStranger.sectionOf(quest), "listed under Collect elsewhere");
        QuestReader r = reader();
        LedgerRow row = r.row(quest, atStranger);
        assertEquals(J + "state.elsewhere", id(row.state()), "never Collect where it cannot be collected");
        assertEquals(Tone.NEUTRAL, row.tone(), "nothing to do here, and nothing refuses it");

        DetailView page = r.page(quest, atStranger);
        assertNull(page.action(ActionSlot.PRIMARY), "no Collect button here");
        assertEquals(J + "hint.collect_from", id(page.hint()));
        assertEquals("Guide Maren", named(page.hint()),
                "the hint names who collects it, not the character in front of the player");
    }

    @Test
    void atItsOwnSiteTheSameQuestStillCollectsInGold() {
        Quest quest = finishedAt(sited("q_site", QuestTurnInSite.character(GUIDE)), STRANGER);
        presentation.names.put(GUIDE, Msg.raw("Guide Maren"));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        assertEquals(NpcQuestSections.Section.READY, atGuide.sectionOf(quest));
        QuestReader r = reader();
        LedgerRow row = r.row(quest, atGuide);
        assertEquals(J + "state.collect", id(row.state()));
        assertEquals(Tone.COLLECT, row.tone());

        DetailView page = r.page(quest, atGuide);
        DetailAction collect = page.action(ActionSlot.PRIMARY);
        assertNotNull(collect, "collected here");
        assertEquals(QuestActions.COLLECT, collect.actionId());
        assertEquals(ActionLook.COLLECT, collect.look());
        assertNull(page.hint(), "nothing to say about where");
    }

    /** In the book, a quest collected only at its site reads Elsewhere and its hint names the site's character. */
    @Test
    void inTheBookASiteBoundQuestReadsElsewhereAndNamesWhoCollectsIt() {
        Quest quest = finishedAt(sited("q_site", QuestTurnInSite.character(GUIDE)), GUIDE);
        presentation.names.put(GUIDE, Msg.raw("Guide Maren"));

        QuestReader r = reader();
        assertEquals(QuestSection.READY, r.sectionOf(quest), "its section is the quest's own, wherever it is read");
        LedgerRow row = r.row(quest);
        assertEquals(J + "state.elsewhere", id(row.state()));
        assertEquals(Tone.NEUTRAL, row.tone());
        assertEquals(J + "state.elsewhere", id(r.compactRow(quest).state()), "the compact row reads the same");
        LedgerModel journal = r.journal(r.listed(), "all", "all", "all", "");
        assertEquals(J + "state.elsewhere", id(journal.sections().get(0).rows().get(0).state()), "and the journal's");

        DetailView page = r.page(quest);
        assertNull(page.action(ActionSlot.PRIMARY), "the engine refuses a placeless payout, so no Collect");
        assertEquals(J + "hint.collect_from", id(page.hint()));
        assertEquals("Guide Maren", named(page.hint()));

        presentation.names.clear();
        assertEquals(J + "hint.collect_at_site", id(reader().page(quest).hint()), "no name known, the plain line");
    }

    /** A quest collected wherever it was taken names that place, in the book and at another character alike. */
    @Test
    void aQuestCollectedWhereItWasTakenNamesThatPlace() {
        Quest quest = finishedAt(sited("q_back", QuestTurnInSite.ACCEPT_SITE), GUIDE);
        presentation.names.put(GUIDE, Msg.raw("Guide Maren"));
        CharacterQuestListing atStranger = new CharacterQuestListing(engine, player, Set.of(STRANGER));
        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, Set.of(GUIDE));

        QuestReader r = reader();
        assertEquals(J + "state.elsewhere", id(r.row(quest).state()));
        assertEquals("Guide Maren", named(r.page(quest).hint()), "the book names where it was taken");
        assertEquals(J + "state.elsewhere", id(r.row(quest, atStranger).state()));
        assertEquals("Guide Maren", named(r.page(quest, atStranger).hint()));

        assertEquals(J + "state.collect", id(r.row(quest, atGuide).state()), "back where it was taken: Collect");
        assertEquals(QuestActions.COLLECT, r.page(quest, atGuide).action(ActionSlot.PRIMARY).actionId());
    }

    /** A quest naming no site is collected anywhere: Collect in gold, its button and no hint, in the book or not. */
    @Test
    void aQuestWithNoSiteCollectsInGoldWhereverItIsRead() {
        Quest quest = plain("q_ready");
        set(quest);
        assertTrue(engine.accept(player, quest));
        engine.markUnclaimed(player, quest);
        presentation.names.put(GUIDE, Msg.raw("Guide Maren"));
        CharacterQuestListing atStranger = new CharacterQuestListing(engine, player, Set.of(STRANGER));

        QuestReader r = reader();
        for (CharacterQuestListing here : Arrays.asList(null, atStranger)) {
            String where = here == null ? "in the book" : "at a character";
            LedgerRow row = r.row(quest, here);
            assertEquals(J + "state.collect", id(row.state()), where);
            assertEquals(Tone.COLLECT, row.tone(), where);
            DetailView page = r.page(quest, here);
            assertEquals(QuestActions.COLLECT, page.action(ActionSlot.PRIMARY).actionId(), where);
            assertEquals(ActionLook.COLLECT, page.action(ActionSlot.PRIMARY).look(), where);
            assertNull(page.hint(), where);
        }
        assertEquals(J + "state.collect", id(r.row(quest).state()), "the book's own row");
        assertEquals(J + "state.collect", id(r.compactRow(quest).state()));
    }

    // ==================== the page ====================

    @Test
    void theTrackToggleIsOnACarriedQuestWithTheShippedTooltip() throws IOException {
        Quest active = plain("q_active");
        Quest available = plain("q_available");
        set(active, available);
        assertTrue(engine.accept(player, active));

        DetailView page = reader().page(active);
        assertNotNull(page.toggle(), "a carried quest is tracked from its page");
        assertFalse(page.toggle().on());
        assertEquals(J + "action.track", id(page.toggle().label()));
        assertEquals(QuestActions.TRACK, page.toggle().actionId());
        assertEquals("ziggfreedcommon.progression.book.tooltip.track", id(page.toggle().tooltip()),
                "the Track toggle says what it does on hover");
        String english = Files.readString(Path.of("src", "main", "resources", "Server", "Languages", "en-US",
                "ziggfreedcommon.progression.lang"), StandardCharsets.UTF_8);
        assertTrue(english.lines().anyMatch(line -> line.startsWith("book.tooltip.track =")),
                "ziggfreedcommon.progression.lang must author 'book.tooltip.track'");

        assertTrue(engine.track(player, active.id()));
        DetailView tracked = reader().page(active);
        assertTrue(tracked.toggle().on());
        assertEquals(J + "action.untrack", id(tracked.toggle().label()));

        assertNull(reader().page(available).toggle(), "a quest not taken has nothing to track");
    }

    /**
     * One reading for both pages: the quest's narrative for where it stands (taken, carried, finished), else its
     * flavor line. The NPC quest page promised per-state lore at the giver; the book now reads it too.
     */
    @Test
    void theLeadIsTheLoreForWhereItStandsElseTheFlavor() {
        ProgressionRuntime.resetForTests();
        try {
            ProgressionRuntime.registrar("test").textSource(new ProgressionTextSource() {
                @Nullable
                @Override
                public Message title(@Nonnull String contentId) {
                    return null;
                }

                @Nullable
                @Override
                public Message flavor(@Nonnull String contentId) {
                    return Msg.raw("flavor of " + contentId);
                }

                @Nullable
                @Override
                public Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
                    return null;
                }

                @Nullable
                @Override
                public Message lore(@Nonnull String contentId, @Nonnull String state) {
                    return ContentTextAsset.Lore.STATE_ACTIVE.equals(state) ? Msg.raw("lore while carried") : null;
                }
            });
            Quest quest = plain("q_lore");
            set(quest);
            assertEquals("flavor of q_lore", reader().page(quest).lead().getRawText(),
                    "no lore before it is taken: the flavor line");
            assertTrue(engine.accept(player, quest));
            assertEquals("lore while carried", reader().page(quest).lead().getRawText(),
                    "carried, the lore for that state");
        } finally {
            ProgressionRuntime.resetForTests();
        }
    }

    @Test
    void requirementsShowOnlyBeforeAcceptance() {
        Quest lockedQuest = locked("q_locked", "q_nothing");
        Quest active = plain("q_active");
        set(lockedQuest, active);
        assertTrue(engine.accept(player, active));

        DetailView page = reader().page(lockedQuest);
        DetailBlock requirements = block(page, "requirements");
        assertNotNull(requirements, "a locked quest says why");
        assertFalse(requirements.lines().isEmpty());
        assertEquals(Tick.LOCKED, requirements.lines().get(0).tick(), "refused, in the locked tick");
        assertEquals(J + "block.requirements", id(requirements.label()));

        presentation.requirement = Msg.raw("Mining 10");
        DetailBlock consumer = block(reader().page(lockedQuest), "requirements");
        assertEquals("Mining 10", consumer.lines().get(0).text().getRawText(), "the consumer's reading first");

        assertNull(block(reader().page(active), "requirements"), "a carried quest has no requirements block");
    }

    @Test
    void orderedStepsReadAsHeadedBlocksWithTicks() {
        Quest quest = ordered("q_ordered", false);
        set(quest);
        assertTrue(engine.accept(player, quest));

        DetailView page = reader().page(quest);
        DetailBlock objectives = block(page, "objectives");
        assertNotNull(objectives);
        assertEquals(J + "block.objectives", id(objectives.label()));
        assertNotNull(objectives.meta(), "the ordering hint is the block's caption");
        assertEquals(2, objectives.lines().size(), "step one's two lines");
        assertEquals(Tick.CURRENT, objectives.lines().get(0).tick());

        DetailBlock step2 = block(page, "step2");
        assertNotNull(step2, "a later step opens its own heading");
        assertEquals(J + "block.step", id(step2.label()));
        assertEquals(1, step2.lines().size());
        assertEquals(Tick.LOCKED, step2.lines().get(0).tick(), "locked until step one is done");

        List<DetailLine> lines = reader().objectiveLines(quest);
        assertEquals(3, lines.size(), "every objective line, in the order the page draws them");
        List<DetailLine> drawn = new ArrayList<>(objectives.lines());
        drawn.addAll(step2.lines());
        for (int i = 0; i < drawn.size(); i++) {
            assertEquals(drawn.get(i).tick(), lines.get(i).tick(), "line " + i + ": the same tick");
            assertEquals(id(drawn.get(i).count()), id(lines.get(i).count()), "line " + i + ": the same count");
            assertEquals(drawn.get(i).count().getFormattedMessage().params,
                    lines.get(i).count().getFormattedMessage().params, "line " + i + ": the same numbers");
            assertEquals(id(drawn.get(i).text()), id(lines.get(i).text()), "line " + i + ": the same step");
        }
    }

    @Test
    void hiddenLockedStepsStayHiddenWhileCarried() {
        Quest quest = ordered("q_hidden", true);
        set(quest);
        assertTrue(engine.accept(player, quest));

        DetailView page = reader().page(quest);
        assertNull(block(page, "step2"), "a hidden locked step draws neither its heading nor its lines");
        assertEquals(2, reader().objectiveLines(quest).size());
    }

    @Test
    void aQuestNotTakenListsItsStepsWithoutCounts() {
        Quest quest = plain("q_available");
        set(quest);
        List<DetailLine> lines = reader().objectiveLines(quest);
        assertEquals(1, lines.size());
        assertNull(lines.get(0).count(), "no wall of zeroes before it is taken");
        assertEquals(Tick.NONE, lines.get(0).tick());
    }

    // ==================== the journal ====================

    @Test
    void theJournalGroupsBySectionInOrderAndDropsEmptySections() {
        Quest available = plain("q_available");
        Quest active = plain("q_active");
        Quest ready = plain("q_ready");
        set(available, active, ready);
        assertTrue(engine.accept(player, active));
        assertTrue(engine.accept(player, ready));
        engine.markUnclaimed(player, ready);

        QuestReader r = reader();
        LedgerModel model = r.journal(r.listed(), "all", "all", "all", "");
        assertEquals(List.of("ready", "progress", "available"), sectionIds(model));
        assertEquals("q_ready", model.firstSelectable(), "the first row of the first open section");
        for (LedgerSection section : model.sections()) {
            assertEquals(J + "section." + section.id(), id(section.label()));
            assertTrue(section.openByDefault());
        }
    }

    @Test
    void notYetWaitingAndCompletedStartClosed() {
        Quest done = plain("q_done");
        Quest waiting = daily("q_waiting");
        Quest lockedQuest = locked("q_locked", "q_nothing");
        set(done, waiting, lockedQuest);
        assertTrue(engine.accept(player, done));
        engine.markCompleted(player, done);
        assertTrue(engine.accept(player, waiting));
        engine.markCompleted(player, waiting);

        QuestReader r = reader();
        LedgerModel model = r.journal(r.listed(), "all", "all", "all", "");
        assertEquals(List.of("not_yet", "waiting", "completed"), sectionIds(model));
        for (LedgerSection section : model.sections()) {
            assertFalse(section.openByDefault(), section.id() + " starts closed");
        }
    }

    @Test
    void aStatusShowsOnlyItsSectionsOpen() {
        Quest active = plain("q_active");
        Quest ready = plain("q_ready");
        Quest done = plain("q_done");
        Quest available = plain("q_available");
        set(active, ready, done, available);
        assertTrue(engine.accept(player, active));
        assertTrue(engine.accept(player, ready));
        engine.markUnclaimed(player, ready);
        assertTrue(engine.accept(player, done));
        engine.markCompleted(player, done);

        QuestReader r = reader();
        assertEquals(List.of("ready", "progress"), sectionIds(r.journal(r.listed(), "progress", "all", "all", "")));
        assertEquals(List.of("available"), sectionIds(r.journal(r.listed(), "available", "all", "all", "")));
        LedgerModel finished = r.journal(r.listed(), "done", "all", "all", "");
        assertEquals(List.of("completed"), sectionIds(finished));
        assertTrue(finished.sections().get(0).openByDefault(), "a chosen status opens its sections");
        assertEquals(List.of("ready", "progress"), sectionIds(r.journal(r.listed(), "active", "all", "all", "")),
                "the old book's status id still reads");
    }

    @Test
    void categoryTagAndSearchFilter() {
        Quest main = Quest.builder("q_main").category("main").tag("farming").objective(mine("mine", 0)).build();
        Quest misc = Quest.builder("q_misc").category("misc").objective(mine("mine", 0)).build();
        set(main, misc);

        QuestReader r = reader();
        assertEquals(List.of("main", "misc"), r.categories(r.listed()));
        assertEquals(List.of("farming"), r.tags(r.listed()));
        assertEquals(List.of("q_misc"), rowIds(r.journal(r.listed(), "all", "misc", "all", "")));
        assertEquals(List.of("q_main"), rowIds(r.journal(r.listed(), "all", "all", "farming", "")));
        assertEquals(List.of("q_main"), rowIds(r.journal(r.listed(), "all", "all", "all", "farm")),
                "a search matches the tags too");
        LedgerModel searched = r.journal(r.listed(), "all", "all", "all", "q");
        assertTrue(searched.sections().stream().allMatch(LedgerSection::openByDefault),
                "a search opens every section it shows");
    }

    @Test
    void aBoardsPlumbingTagsNeverReachTheTagDropdown() {
        Quest board = Quest.builder("q_board").category("main").tag("bounty_internal").objective(mine("mine", 0))
                .build();
        set(board);
        presentation.managed.add(board.id());
        assertTrue(engine.accept(player, board));
        QuestReader r = reader();
        assertTrue(r.tags(r.listed()).isEmpty());
    }

    @Test
    void aSectionKeepsEveryRowAndCapsAtForty() {
        List<Quest> many = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            many.add(Quest.builder(String.format(Locale.ROOT, "q_%02d", i)).category("main")
                    .listOrder(i).objective(mine("mine", 0)).build());
        }
        engine.setQuests(many);
        QuestReader r = reader();
        LedgerModel model = r.journal(r.listed(), "all", "all", "all", "");
        LedgerSection available = model.sections().get(0);
        assertEquals(45, available.rows().size(), "the reader never cuts a list silently");
        assertEquals(LedgerSection.DEFAULT_CAP, available.cap(), "the kit shows 40, then Show more");
        assertEquals("q_00", available.rows().get(0).id(), "authored order inside a section");
        assertEquals("q_44", available.rows().get(44).id());
    }

    // ==================== the header ====================

    @Test
    void theHeaderCountsInProgressToCollectAndTracking() {
        Quest active = plain("q_active");
        Quest ready = plain("q_ready");
        set(active, ready);
        assertTrue(engine.accept(player, active));
        assertTrue(engine.accept(player, ready));
        engine.markUnclaimed(player, ready);
        assertTrue(engine.track(player, active.id()));

        List<Stat> stats = reader().stats();
        assertEquals(3, stats.size());
        assertEquals(J + "stat.progress", id(stats.get(0).label()));
        assertEquals(J + "stat.collect", id(stats.get(1).label()));
        assertEquals(Tone.COLLECT, stats.get(1).tone(), "something to collect reads gold");
        assertEquals(J + "stat.tracking", id(stats.get(2).label()));
        assertEquals(J + "subtitle", id(reader().subtitle()));
    }

    /**
     * "In progress N/M" is read against the cap, so N is the number the cap measures ({@code logSlotsUsed}): a
     * carried quest out of season and a board contract that keeps no log slot are left out of the stat and the
     * subtitle alike, and the header never reads full while the log still takes a quest.
     */
    @Test
    void theHeadersInProgressCountsWhatTheLogCapCounts() {
        AtomicBoolean running = new AtomicBoolean(true);
        Quest seasonal = Quest.builder("q_seasonal").category("main").objective(mine("mine", 0))
                .available(running::get).build();
        Quest contract = Quest.builder("q_contract").category("main").occupiesLog(false)
                .objective(mine("mine", 0)).build();
        Quest active = plain("q_active");
        Quest next = plain("q_next");
        QuestEngine capped = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .maxActive(2).clock(() -> now).build();
        capped.setQuests(List.of(seasonal, contract, active, next));
        presentation.managed.add(contract.id());
        assertTrue(capped.accept(player, seasonal));
        assertTrue(capped.accept(player, contract));
        assertTrue(capped.accept(player, active));
        running.set(false);
        assertTrue(capped.canAccept(player, next).allowed(),
                "a quest out of season and a contract hold no log slot, so the log takes another quest");

        QuestReader r = QuestReader.of(capped, player, presentation, player.id(), now);
        Message inProgress = r.stats().get(0).value();
        assertEquals(1L, number(inProgress, "0"), "the one log quest on offer, as the cap counts it");
        assertEquals(2L, number(inProgress, "1"), "read over the cap");
        assertEquals(1L, number(r.subtitle(), "0"), "the subtitle says the same number");
    }

    /** A message's numeric param, whichever width it was bound at. */
    private static long number(@Nonnull Message message, @Nonnull String param) {
        FormattedMessage formatted = message.getFormattedMessage();
        assertNotNull(formatted.params, "a count binds as a typed number");
        return switch (formatted.params.get(param)) {
            case LongParamValue value -> value.value;
            case IntParamValue value -> value.value;
            case null, default -> throw new AssertionError("param " + param + " is not a typed number");
        };
    }

    // ==================== the lang file ====================

    @Test
    void everyJournalKeyTheCodeReadsIsAuthoredInEnglish() throws IOException {
        Path lang = Path.of("src", "main", "resources", "Server", "Languages", "en-US", "ziggfreedcommon.journal.lang");
        Set<String> authored = new HashSet<>();
        for (String line : Files.readAllLines(lang, StandardCharsets.UTF_8)) {
            int eq = line.indexOf(" = ");
            if (!line.startsWith("#") && eq > 0) {
                authored.add(line.substring(0, eq).trim());
            }
        }
        Path java = Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives");
        Pattern literal = Pattern.compile("\"((?:section|state|stat|status|category|tag|block|action|hint|progress|"
                + "empty|reward|search)\\.[a-z_.]+|subtitle)\"");
        List<String> missing = new ArrayList<>();
        for (Path file : List.of(java.resolve("journal").resolve("QuestReader.java"),
                java.resolve("journal").resolve("QuestActions.java"),
                java.resolve("book").resolve("quest").resolve("QuestJournalTab.java"))) {
            Matcher m = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!authored.contains(m.group(1))) {
                    missing.add(file.getFileName() + ": " + m.group(1));
                }
            }
        }
        for (QuestSection section : QuestSection.values()) {
            if (!authored.contains(section.labelKey())) {
                missing.add("QuestSection: " + section.labelKey());
            }
        }
        assertEquals(List.of(), missing, "keys the journal reads that ziggfreedcommon.journal.lang does not author");
    }

    // ==================== helpers ====================

    @Nullable
    private static DetailBlock block(@Nonnull DetailView page, @Nonnull String blockId) {
        for (DetailBlock block : page.blocks()) {
            if (block.id().equals(blockId)) {
                return block;
            }
        }
        return null;
    }

    @Nonnull
    private static List<String> sectionIds(@Nonnull LedgerModel model) {
        return model.sections().stream().map(LedgerSection::id).toList();
    }

    @Nonnull
    private static List<String> rowIds(@Nonnull LedgerModel model) {
        List<String> ids = new ArrayList<>();
        for (LedgerSection section : model.sections()) {
            for (LedgerRow row : section.rows()) {
                ids.add(row.id());
            }
        }
        return ids;
    }
}
