package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestEngine.ItemDropDue;
import com.ziggfreed.common.quest.QuestItemDrop;
import com.ziggfreed.common.quest.QuestItemDrop.Source;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.subject.Subject;

/**
 * A step's {@code Drop} leaf: it decodes onto the runtime quest with its defaults, and the engine owes
 * its item only to a player carrying the quest whose step is unlocked and still wants it, from the
 * source it names, never more than the step still asks for.
 */
class QuestDropLeafTest {

    private static final String QUEST = """
            { "Objectives": {
                "beat_king":   { "Kind": "ENCOUNTER_DEFEATED", "Target": "yourmod_Ogre_King", "MatchMode": "EXACT",
                                 "Order": 1 },
                "bring_crest": { "Kind": "TURN_IN", "Target": "Ogre_Crest", "MatchMode": "EXACT", "Amount": 2,
                                 "Order": 2, "Drop": { "Encounters": ["yourmod_Ogre_King"], "Count": 5 } },
                "pelts":       { "Kind": "PICKUP_ITEM", "Target": "Wolf_Pelt", "MatchMode": "EXACT", "Amount": 3,
                                 "Drop": { "Roles": ["Wolf_Grey", " "], "Chance": 0.25 } },
                "kills":       { "Kind": "KILL_ENTITY", "Target": "Ogre_Brute", "MatchMode": "EXACT", "Amount": 5,
                                 "Drop": { "Roles": ["Ogre_Brute"] } } } }
            """;

    @Test
    void theLeafDecodesOntoTheRuntimeQuestWithItsDefaults() throws Exception {
        Quest quest = decodeRoot(QUEST, "ogre_trouble").toDefinition(null).quest();

        QuestItemDrop crest = quest.stepDrop("bring_crest");
        assertNotNull(crest, "the step's Drop rides the quest under its step id");
        assertEquals(List.of("yourmod_Ogre_King"), crest.encounters());
        assertTrue(crest.roles().isEmpty());
        assertEquals(1.0, crest.chance(), "an unauthored Chance always drops");
        assertEquals(5, crest.count());

        QuestItemDrop pelt = quest.stepDrop("pelts");
        assertNotNull(pelt);
        assertEquals(List.of("Wolf_Grey"), pelt.roles(), "a blank entry names nothing");
        assertEquals(0.25, pelt.chance());
        assertEquals(1, pelt.count(), "an unauthored Count hands over one");
        assertTrue(pelt.lands(0.2) && !pelt.lands(0.3));

        assertNull(quest.stepDrop("beat_king"), "a step that wrote none has none");
        assertEquals(3, quest.stepDrops().size());
    }

    @Test
    void aChildRetuningOneDropLeafKeepsTheParentsOthers() throws Exception {
        QuestAsset parent = decodeRoot("""
                { "Objectives": { "bring": { "Kind": "TURN_IN", "Target": "Ogre_Crest", "Amount": 2,
                    "Drop": { "Roles": ["Ogre_Brute"], "Encounters": ["yourmod_Ogre_King"], "Count": 2 } } } }
                """, "ogre_base");
        QuestAsset child = decode("""
                { "Objectives": { "bring": { "Drop": { "Chance": 0.5 } } } }
                """, "ogre_child", "ogre_base", parent);

        QuestItemDrop drop = child.toDefinition(null).quest().stepDrop("bring");
        assertNotNull(drop, "the inherited step keeps its Drop");
        assertEquals(0.5, drop.chance(), "the child's own leaf wins");
        assertEquals(List.of("Ogre_Brute"), drop.roles(), "a sibling leaf of the block it touched survives");
        assertEquals(List.of("yourmod_Ogre_King"), drop.encounters());
        assertEquals(2, drop.count());
    }

    @Test
    void onlyACarriedUnlockedStepThatStillWantsTheItemIsOwedIt() throws Exception {
        Quest quest = decodeRoot(QUEST, "ogre_trouble").toDefinition(null).quest();
        Map<String, Integer> bag = new HashMap<>();
        QuestPossessionProbe holds = (subject, itemId, count) -> bag.getOrDefault(itemId, 0) >= count;
        QuestEngine engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .possessionProbe(holds)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        Subject player = Subject.of(UUID.randomUUID(), "tester");

        assertTrue(engine.dropsFrom(Source.ENCOUNTER, "YOURMOD_OGRE_KING"), "ids compare ignoring case");
        assertFalse(engine.dropsFrom(Source.ROLE, "yourmod_Ogre_King"), "a fight is no kill: one source each");
        assertTrue(engine.itemDropsDue(player, Source.ROLE, "Wolf_Grey").isEmpty(),
                "a player not carrying the quest gets nothing");

        assertTrue(engine.accept(player, quest));
        assertTrue(engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King").isEmpty(),
                "a step still locked behind an earlier one gets nothing");
        assertTrue(engine.itemDropsDue(player, Source.ROLE, "Wolf_Black").isEmpty(), "a source it does not name");
        assertTrue(engine.itemDropsDue(player, Source.ROLE, "Ogre_Brute").isEmpty(),
                "a Drop on a step that is neither a hand-in nor a pickup never drops");

        List<ItemDropDue> pelts = engine.itemDropsDue(player, Source.ROLE, "Wolf_Grey");
        assertEquals(1, pelts.size());
        assertEquals("Wolf_Pelt", pelts.get(0).itemId());
        assertTrue(pelts.get(0).countsOnArrival());
        bag.put("Wolf_Pelt", 10);
        assertEquals(1, engine.itemDropsDue(player, Source.ROLE, "Wolf_Grey").get(0).upTo(),
                "a pickup step counts arrivals, so what the bag holds does not stop it");

        engine.dispatch(player, "ENCOUNTER_DEFEATED", "yourmod_Ogre_King", null, 1L);
        List<ItemDropDue> crests = engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King");
        assertEquals(1, crests.size(), "the win that unlocked the hand-in is owed its item");
        assertFalse(crests.get(0).countsOnArrival());
        assertEquals(2, crests.get(0).upTo(), "Count 5 is capped at the 2 the step asks for");
        bag.put("Ogre_Crest", 1);
        assertEquals(1, engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King").get(0).upTo(),
                "the bag's one counts toward the two");
        bag.put("Ogre_Crest", 2);
        assertTrue(engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King").isEmpty(),
                "a player already carrying the asked amount stops getting it");

        engine.dispatch(player, "PICKUP_ITEM", "Wolf_Pelt", null, 3L);
        assertTrue(engine.itemDropsDue(player, Source.ROLE, "Wolf_Grey").isEmpty(), "a finished step gets nothing");
    }

    @Test
    void whatWaitsUncollectedCountsAsHeld() throws Exception {
        Quest quest = decodeRoot(QUEST, "ogre_trouble").toDefinition(null).quest();
        Map<String, Integer> bag = new HashMap<>();
        Map<String, Integer> claimed = new HashMap<>();
        QuestEngine engine = QuestEngine.builder()
                .store(new InMemoryQuestProgressStore())
                .possessionProbe((subject, itemId, count) -> bag.getOrDefault(itemId, 0) >= count)
                .nativeEvents(false)
                .warn(message -> { })
                .build();
        engine.setQuests(List.of(quest));
        Subject player = Subject.of(UUID.randomUUID(), "tester");
        QuestEngine.ItemsWaiting waiting = (q, step) -> claimed.getOrDefault(step.id(), 0);
        assertTrue(engine.accept(player, quest));
        engine.dispatch(player, "ENCOUNTER_DEFEATED", "yourmod_Ogre_King", null, 1L);

        claimed.put("bring_crest", 1);
        assertEquals(1, engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King", waiting).get(0).upTo(),
                "one of the two waiting leaves one owed");
        bag.put("Ogre_Crest", 1);
        assertTrue(engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King", waiting).isEmpty(),
                "a hand-in counts the bag and what waits together");
        assertEquals(1, engine.itemDropsDue(player, Source.ENCOUNTER, "yourmod_Ogre_King").get(0).upTo(),
                "the form with nothing waiting reads the bag alone");

        claimed.put("pelts", 2);
        assertEquals(1, engine.itemDropsDue(player, Source.ROLE, "Wolf_Grey", waiting).get(0).upTo(),
                "a pickup step owing three with two waiting still wants one");
        claimed.put("pelts", 3);
        assertTrue(engine.itemDropsDue(player, Source.ROLE, "Wolf_Grey", waiting).isEmpty(),
                "a pickup step whose whole count waits gets nothing more");
    }
}
