package com.ziggfreed.common.objectives.questlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The lifted classification the NPC quest page and the overhead indicators both read, driven over
 * a real engine with an in-memory store: which quests a character lists for a player and which
 * section each lands in, through the whole lifecycle of one giver's quests.
 *
 * <p>The offer table is registered here over the test's own engine, since the library's default
 * provider reads the shared runtime and this engine is private to the test.
 */
class CharacterQuestListingTest {

    private static final String GUIDE = "guide";
    private static final Set<String> AT_GUIDE = Set.of(GUIDE);

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

    private static boolean containsIgnoreCase(Collection<String> ids, String wanted) {
        for (String id : ids) {
            if (id.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static Quest gather(String id) {
        return Quest.builder(id)
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
    }

    private static Quest reportBack(String id) {
        return Quest.builder(id)
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(GUIDE).build())
                .build();
    }

    @Test
    void aQuestOnOfferIsAvailableAtItsGiverAndNowhereElse() {
        Quest quest = gather("q_gather");
        engine.setQuests(List.of(quest));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, AT_GUIDE);
        assertEquals(List.of(quest), atGuide.questsHere());
        assertEquals(Section.AVAILABLE, atGuide.sectionOf(quest));
        assertNull(atGuide.turnInHere(quest));

        CharacterQuestListing atStranger = new CharacterQuestListing(engine, player, Set.of("stranger"));
        assertTrue(atStranger.questsHere().isEmpty());
    }

    @Test
    void aQuestTakenHereStaysOnTheGiversListWhileCarried() {
        Quest quest = gather("q_gather");
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, GUIDE));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, AT_GUIDE);
        assertTrue(atGuide.takenHere(quest));
        assertFalse(atGuide.readyHere(quest), "its outstanding step is a block, not this character");
        assertEquals(List.of(quest), atGuide.questsHere(), "given here is engine data");
        assertEquals(Section.ACTIVE, atGuide.sectionOf(quest));
        assertNotNull(atGuide.outstandingStep(quest));
        assertEquals("mine", atGuide.outstandingStep(quest).id());
    }

    @Test
    void aReportBackErrandSettlesAtTheCharacterItIsLockedTo() {
        Quest quest = reportBack("q_report");
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, GUIDE));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, AT_GUIDE);
        assertTrue(atGuide.readyHere(quest));
        assertTrue(atGuide.settlesHere(quest));
        assertEquals(Section.TURN_IN, atGuide.sectionOf(quest));
        CharacterQuestListing.TurnIn turnIn = atGuide.turnInHere(quest);
        assertNotNull(turnIn);
        assertEquals("tell", turnIn.step().id());
        assertEquals(GUIDE, turnIn.atId(), "the id the hand-in must be performed at");

        CharacterQuestListing elsewhere = new CharacterQuestListing(engine, player, Set.of("stranger"));
        assertFalse(elsewhere.settlesHere(quest));
    }

    @Test
    void aFinishedQuestWaitingToBeCollectedIsReadyWhereItMayBeCollected() {
        Quest quest = gather("q_gather");
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, GUIDE));
        engine.markUnclaimed(player, quest);
        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, engine.status(player, quest));

        CharacterQuestListing atGuide = new CharacterQuestListing(engine, player, AT_GUIDE);
        assertTrue(atGuide.canCollectHere(quest));
        assertEquals(GUIDE, atGuide.collectionSite(quest));
        assertEquals(Section.READY, atGuide.sectionOf(quest));
        assertEquals(List.of(quest), atGuide.questsHere());
    }

    @Test
    void anAliasTheCharacterAnswersToCountsAsTheCharacter() {
        Quest quest = reportBack("q_report");
        engine.setQuests(List.of(quest));
        assertTrue(engine.accept(player, quest, "Guide_Alias"));

        CharacterQuestListing listing = new CharacterQuestListing(engine, player, Set.of("Guide_Alias", GUIDE));
        assertTrue(listing.takenHere(quest), "the accept site matches an alias, case-insensitively");
        assertTrue(listing.settlesHere(quest), "the lock id matches the primary");
        assertEquals(Section.TURN_IN, listing.sectionOf(quest));
    }
}
