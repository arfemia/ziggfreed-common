package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.journal.QuestActions;
import com.ziggfreed.common.objectives.journal.QuestPresentation;
import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Entry;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * What the NPC quest page lists and opens on, with no page behind it: the HERE list (what the character holds out
 * plus what is carried whose business is here) and the MINE list (everything carried), each as the kit's sectioned
 * ledger in the order a player can act on it; the routed quest leading the list in its own open section and opening
 * the page; a surviving selection kept; and the sections a player has to act on open, the rest closed.
 */
class NpcQuestPagePlanTest {

    private static final String GUIDE = "guide";

    private QuestEngine engine;
    private Subject player;

    @BeforeEach
    void engine() {
        engine = QuestEngine.builder().nativeEvents(false).warn(message -> { })
                .possessionProbe((itemId, count) -> true)
                .maxActive(10)
                .build();
        player = Subject.of(UUID.randomUUID(), "tester");
        NpcOfferProviders.register("test", "test", (subject, answersTo) -> {
            List<NpcOffer> out = new ArrayList<>();
            for (Quest quest : engine.quests()) {
                if (quest.npcViewId() != null && containsIgnoreCase(answersTo, quest.npcViewId())
                        && engine.isOfferable(subject, quest)) {
                    out.add(NpcOffer.available(quest.id(), null));
                }
            }
            return out;
        });
    }

    @AfterEach
    void clearOffers() {
        NpcOfferProviders.clear();
    }

    // ==================== the two lists ====================

    @Test
    void theHereListIsWhatTheCharacterHoldsOutAndWhatIsCarriedForIt() {
        Quest offer = gather("q_offer", GUIDE);
        Quest carried = gather("q_carried", GUIDE);
        Quest report = reportBack("q_report");
        Quest elsewhere = gather("q_elsewhere", null);
        engine.setQuests(List.of(offer, carried, report, elsewhere));
        assertTrue(engine.accept(player, carried, GUIDE));
        assertTrue(engine.accept(player, report, GUIDE));
        assertTrue(engine.accept(player, elsewhere));

        CharacterQuestListing listing = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        LedgerModel here = model(NpcQuestPagePlan.quests(NpcQuestPagePlan.TAB_HERE, listing, engine, player),
                listing, null);

        assertEquals(List.of("turn_in", "active", "available"), sectionIds(here),
                "most actionable first: a step to hand over, what is carried, what can be taken");
        assertEquals(List.of("q_report", "q_carried", "q_offer"), rowIds(here));
        assertFalse(here.contains("q_elsewhere"), "a quest with no business here is not on the character's list");
    }

    @Test
    void theMineListIsEverythingThePlayerCarriesWhereverItCameFrom() {
        Quest offer = gather("q_offer", GUIDE);
        Quest carried = gather("q_carried", GUIDE);
        Quest elsewhere = gather("q_elsewhere", null);
        engine.setQuests(List.of(offer, carried, elsewhere));
        assertTrue(engine.accept(player, carried, GUIDE));
        assertTrue(engine.accept(player, elsewhere));

        CharacterQuestListing listing = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        LedgerModel mine = model(NpcQuestPagePlan.quests(NpcQuestPagePlan.TAB_MINE, listing, engine, player),
                listing, null);

        assertEquals(List.of("active"), sectionIds(mine));
        assertEquals(Set.of("q_carried", "q_elsewhere"), Set.copyOf(rowIds(mine)));
        assertFalse(mine.contains("q_offer"), "an offer is not carried");
    }

    @Test
    void aRoutedQuestThatIsOnlyCarriedOpensTheMineList() {
        Quest offer = gather("q_offer", GUIDE);
        Quest elsewhere = gather("q_elsewhere", null);
        List<Quest> here = List.of(offer);
        List<Quest> mine = List.of(elsewhere);

        assertEquals(NpcQuestPagePlan.TAB_MINE, NpcQuestPagePlan.tab(NpcQuestPagePlan.TAB_HERE, "q_elsewhere",
                here, mine), "the routed quest has to be reachable");
        assertEquals(NpcQuestPagePlan.TAB_HERE, NpcQuestPagePlan.tab(NpcQuestPagePlan.TAB_HERE, "q_offer",
                here, mine));
        assertEquals(NpcQuestPagePlan.TAB_HERE, NpcQuestPagePlan.tab(NpcQuestPagePlan.TAB_HERE, "q_nowhere",
                here, mine), "a routed quest on neither list leaves the list the player asked for");
        assertEquals(NpcQuestPagePlan.TAB_MINE, NpcQuestPagePlan.tab(NpcQuestPagePlan.TAB_MINE, null, here, mine));
        assertEquals(NpcQuestPagePlan.TAB_HERE, NpcQuestPagePlan.tab("anything else", null, here, mine),
                "an unknown list reads as Here");
    }

    // ==================== sections ====================

    @Test
    void everySectionReadsInTheOrderAPlayerCanActOnIt() {
        LedgerModel model = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(List.of(
                Entry.of("done", Section.DONE, false),
                Entry.of("locked", Section.LOCKED, false),
                Entry.of("cooldown", Section.COOLDOWN, false),
                Entry.of("parked", Section.PARKED, false),
                Entry.of("available", Section.AVAILABLE, false),
                Entry.of("active", Section.ACTIVE, false),
                Entry.of("turnin", Section.TURN_IN, false),
                Entry.of("ready", Section.READY, false))), NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label,
                Map.of());

        assertEquals(List.of("ready", "turn_in", "active", "available", "parked", "cooldown", "locked", "done"),
                sectionIds(model));
        assertEquals("READY", model.sections().get(0).label().getRawText(), "each head carries its section's name");
    }

    @Test
    void theSectionsAPlayerActsOnOpenAndTheRestStayClosed() {
        LedgerModel model = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(List.of(
                Entry.of("ready", Section.READY, false),
                Entry.of("turnin", Section.TURN_IN, false),
                Entry.of("active", Section.ACTIVE, false),
                Entry.of("available", Section.AVAILABLE, false),
                Entry.of("parked", Section.PARKED, false),
                Entry.of("cooldown", Section.COOLDOWN, false),
                Entry.of("locked", Section.LOCKED, false),
                Entry.of("done", Section.DONE, false))), NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label,
                Map.of());

        for (LedgerSection section : model.sections()) {
            boolean shouldOpen = !Set.of("cooldown", "locked", "done").contains(section.id());
            assertEquals(shouldOpen, section.openByDefault(), section.id());
        }
    }

    @Test
    void aSectionOverItsCapShowsTheRestOnAsking() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < LedgerSection.DEFAULT_CAP + 5; i++) {
            many.add(Entry.of(String.format(Locale.ROOT, "q%03d", i), Section.AVAILABLE, false));
        }
        LedgerModel capped = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(many), NpcQuestPagePlanTest::row,
                NpcQuestPagePlanTest::label, Map.of());
        assertEquals(LedgerSection.DEFAULT_CAP, capped.sections().get(0).cap());

        LedgerModel shown = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(many), NpcQuestPagePlanTest::row,
                NpcQuestPagePlanTest::label, NpcQuestPagePlan.showMore(Map.of(), "available"));
        assertEquals(2 * LedgerSection.DEFAULT_CAP, shown.sections().get(0).cap(), "Show more raises it by one cap");
    }

    // ==================== the routed quest and the selection ====================

    @Test
    void aRoutedQuestLeadsTheListInItsOwnOpenSection() {
        LedgerModel model = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(List.of(
                Entry.of("ready", Section.READY, false),
                Entry.of("active", Section.ACTIVE, false),
                Entry.of("other_locked", Section.LOCKED, false),
                Entry.of("routed", Section.LOCKED, true))), NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label,
                Map.of());

        LedgerSection first = model.sections().get(0);
        assertEquals("locked", first.id(), "its section heads the list: the row is first and its head is true");
        assertEquals("routed", first.rows().get(0).id(), "there is no scroll-to on a page: first IS take me to it");
        assertTrue(first.openByDefault(), "a closed section would hide the quest the player was routed to");
        assertEquals(List.of("routed", "other_locked", "ready", "active"), rowIds(model));
        assertEquals("routed", NpcQuestPagePlan.select(model, "routed", "active"),
                "the routed quest beats a stale selection");
    }

    @Test
    void aSurvivingSelectionBeatsTheFirstRowAndAGoneOneFallsToTheFirstOpenRow() {
        LedgerModel model = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(List.of(
                Entry.of("locked", Section.LOCKED, false),
                Entry.of("available", Section.AVAILABLE, false),
                Entry.of("active", Section.ACTIVE, false))), NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label,
                Map.of());

        assertEquals("available", NpcQuestPagePlan.select(model, null, "available"),
                "a refresh after an action stays on the quest");
        assertEquals("active", NpcQuestPagePlan.select(model, null, "gone"), "the first row of the first open section");
        assertEquals("active", NpcQuestPagePlan.select(model, "elsewhere", null),
                "a routed quest on another list is ignored here");
        assertNull(NpcQuestPagePlan.select(NpcQuestPagePlan.model(List.of(), NpcQuestPagePlanTest::row,
                NpcQuestPagePlanTest::label, Map.of()), "routed", "other"), "an empty list selects nothing");
    }

    @Test
    void theSelectedRowsSectionOpensUnlessThePlayerClosedIt() {
        LedgerModel model = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(List.of(
                Entry.of("locked", Section.LOCKED, false),
                Entry.of("done", Section.DONE, false))), NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label,
                Map.of());
        String selected = NpcQuestPagePlan.select(model, null, null);
        assertEquals("locked", selected, "with nothing open, the first row of any section");

        Set<String> open = NpcQuestPagePlan.openSections(model, Set.of(), selected);
        assertTrue(LedgerPainter.isOpen(model.sections().get(0), open), "the page shows a row the list shows");
        assertFalse(LedgerPainter.isOpen(model.sections().get(1), open));

        Set<String> closedByThePlayer = LedgerPainter.withSection(Set.of(), "locked", false);
        assertFalse(LedgerPainter.isOpen(model.sections().get(0),
                NpcQuestPagePlan.openSections(model, closedByThePlayer, selected)), "the player's own answer stands");
    }

    @Test
    void theCountIsTheQuestsListedAndTheSectionHoldingARowIsFound() {
        LedgerModel model = NpcQuestPagePlan.model(NpcQuestPagePlan.sort(List.of(
                Entry.of("a", Section.ACTIVE, false),
                Entry.of("b", Section.ACTIVE, false),
                Entry.of("c", Section.DONE, false))), NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label,
                Map.of());
        assertEquals(3, NpcQuestPagePlan.rowCount(model));
        assertEquals("done", NpcQuestPagePlan.sectionHolding(model, "c"));
        assertNull(NpcQuestPagePlan.sectionHolding(model, "gone"));
        assertEquals(Section.DONE, NpcQuestPagePlan.sectionNamed("done"));
        assertNull(NpcQuestPagePlan.sectionNamed("nonsense"));
    }

    // ==================== the page ====================

    /**
     * Smoke 18: the giver's page is the book's page, built by the same reader; only the place's buttons differ. A
     * giver-bound quest is never taken from the book, and is taken here, at its giver.
     */
    @Test
    void theGiversPageIsTheBooksPageWithTheGiversButtons() {
        Quest quest = gather("q_offer", GUIDE);
        engine.setQuests(List.of(quest));
        QuestReader reader = QuestReader.of(engine, player, QuestPresentation.defaults(), null, 0L);
        CharacterQuestListing listing = new CharacterQuestListing(engine, player, Set.of(GUIDE));
        CharacterQuestListing here = NpcQuestPagePlan.place(GUIDE, listing);

        DetailView atGiver = NpcQuestPagePlan.page(reader, quest, here);
        DetailView inBook = reader.page(quest);

        assertEquals(reading(inBook), reading(atGiver), "the same title, meta, text, progress, toggle and blocks");
        assertNull(inBook.action(ActionSlot.PRIMARY), "the book never takes a giver-bound quest");
        DetailAction accept = atGiver.action(ActionSlot.PRIMARY);
        assertNotNull(accept, "its giver does");
        assertEquals(QuestActions.ACCEPT, QuestActions.dispatch(ActionSlot.PRIMARY, quest, reader, here));
    }

    @Test
    void withNobodyInFrontOfThePlayerThePageIsTheBooksButtonsAndAll() {
        Quest quest = gather("q_carried", GUIDE);
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, GUIDE));
        QuestReader reader = QuestReader.of(engine, player, QuestPresentation.defaults(), null, 0L);
        CharacterQuestListing nobody = new CharacterQuestListing(engine, player, Set.of());

        assertNull(NpcQuestPagePlan.place(null, nobody), "no place to read the buttons at");
        DetailView page = NpcQuestPagePlan.page(reader, quest, NpcQuestPagePlan.place(null, nobody));
        DetailView inBook = reader.page(quest);

        assertEquals(reading(inBook), reading(page));
        assertEquals(actions(inBook), actions(page));
    }

    // ==================== helpers ====================

    @Nonnull
    private static LedgerModel model(@Nonnull List<Quest> quests, @Nonnull CharacterQuestListing listing,
            String highlight) {
        return NpcQuestPagePlan.model(NpcQuestPagePlan.entries(quests, listing::sectionOf, highlight),
                NpcQuestPagePlanTest::row, NpcQuestPagePlanTest::label, Map.of());
    }

    @Nonnull
    private static LedgerRow row(@Nonnull String id) {
        return new LedgerRow(id, Msg.raw(id), null, Picture.NONE, Tone.NEUTRAL, null, null, null, Mark.NONE, false);
    }

    @Nonnull
    private static Message label(@Nonnull Section section) {
        return Msg.raw(section.name());
    }

    /** Everything a page reads as, but its action bar: the message keys or raw words, the numbers, the blocks. */
    @Nonnull
    private static List<String> reading(@Nonnull DetailView view) {
        List<String> out = new ArrayList<>();
        out.add("title " + sig(view.title()));
        out.add("meta " + sig(view.meta()));
        out.add("sub " + sig(view.subMeta()));
        out.add("lead " + sig(view.lead()));
        out.add("progress " + view.progress());
        out.add("toggle " + (view.toggle() == null ? "-" : sig(view.toggle().label())));
        out.add("badges " + view.badges().size());
        for (DetailBlock block : view.blocks()) {
            out.add("block " + block.id() + " " + sig(block.label()));
            for (DetailLine line : block.lines()) {
                out.add("  line " + sig(line.text()) + " " + sig(line.count()) + " " + line.tick());
            }
        }
        return out;
    }

    @Nonnull
    private static List<String> actions(@Nonnull DetailView view) {
        List<String> out = new ArrayList<>();
        for (ActionSlot slot : ActionSlot.values()) {
            DetailAction action = view.action(slot);
            out.add(slot + " " + (action == null ? "-" : sig(action.label()) + " " + action.look()));
        }
        out.add("hint " + sig(view.hint()));
        return out;
    }

    @Nonnull
    private static String sig(Message message) {
        if (message == null) {
            return "-";
        }
        return message.getMessageId() != null ? message.getMessageId() : String.valueOf(message.getRawText());
    }

    @Nonnull
    private static List<String> sectionIds(@Nonnull LedgerModel model) {
        List<String> ids = new ArrayList<>();
        for (LedgerSection section : model.sections()) {
            ids.add(section.id());
        }
        return ids;
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

    private static boolean containsIgnoreCase(@Nonnull Collection<String> ids, @Nonnull String wanted) {
        for (String id : ids) {
            if (id.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static Quest gather(@Nonnull String id, String giver) {
        Quest.Builder builder = Quest.builder(id)
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build());
        if (giver != null) {
            builder.npcViewId(giver);
        }
        return builder.build();
    }

    @Nonnull
    private static Quest reportBack(@Nonnull String id) {
        return Quest.builder(id)
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(GUIDE).build())
                .build();
    }
}
