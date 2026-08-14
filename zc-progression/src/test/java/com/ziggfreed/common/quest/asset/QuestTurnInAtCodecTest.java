package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decode;
import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.quest.QuestTurnInSite;

/**
 * The {@code TurnInAt} leaf: its three authored forms, what each one folds to, and that a
 * {@code Parent} carries it down like every other leaf.
 *
 * <p>The leaf is deliberately a dual-form scalar rather than a group, so the commonest answer is one
 * word. Both spellings of the giver form have to land on the same site, or a file would behave
 * differently depending on which one its author reached for.
 */
class QuestTurnInAtCodecTest {

    @Test
    void trueAndTheWordGiverBothMeanWhoeverOffersTheQuest() throws Exception {
        String json = """
                { "Npc": { "ViewId": "guide" }, "TurnInAt": %s,
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "Stone" } } }
                """;

        for (String authored : new String[] {"true", "\"giver\"", "\"GIVER\""}) {
            QuestTurnInSite site = decodeRoot(json.formatted(authored), "q").toDefinition(null).turnInAt();
            assertNotNull(site, authored + " must bind the quest to a place");
            assertEquals(QuestTurnInSite.Kind.CHARACTER, site.kind());
            assertEquals("guide", site.id(), authored + " must resolve to the giver");
        }
    }

    @Test
    void anIdBindsTheQuestToThatCharacterWhoeverGaveIt() throws Exception {
        QuestTurnInSite site = decodeRoot("""
                { "Npc": { "ViewId": "guide" }, "TurnInAt": "quartermaster",
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "Stone" } } }
                """, "q").toDefinition(null).turnInAt();

        assertNotNull(site);
        assertEquals(QuestTurnInSite.Kind.CHARACTER, site.kind());
        assertEquals("quartermaster", site.id(),
                "the collection site is its own statement, not a second spelling of the giver");
    }

    @Test
    void theAcceptSentinelBindsItToWhereverItWasTaken() throws Exception {
        QuestTurnInSite site = decodeRoot("""
                { "TurnInAt": "@accept",
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "Stone" } } }
                """, "q").toDefinition(null).turnInAt();

        assertNotNull(site);
        assertEquals(QuestTurnInSite.Kind.ACCEPT_SITE, site.kind());
        assertNull(site.id(), "there is nobody to name: the place is whatever the player took it from");
    }

    @Test
    void anUnauthoredLeafLeavesTheQuestCollectableAnywhere() throws Exception {
        assertNull(decodeRoot("""
                { "Npc": { "ViewId": "guide" },
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "Stone" } } }
                """, "q").toDefinition(null).turnInAt(),
                "the default is the great majority of content and must need no authoring");
    }

    @Test
    void aGiverBoundQuestWithNoGiverBindsToNobodyRatherThanToAnywhere() throws Exception {
        QuestTurnInSite site = decodeRoot("""
                { "TurnInAt": true,
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "Stone" } } }
                """, "q").toDefinition(null).turnInAt();

        assertNotNull(site, "falling back to anywhere would hide the mistake behind working behaviour");
        assertEquals(QuestTurnInSite.Kind.CHARACTER, site.kind());
        assertNull(site.id());
    }

    @Test
    void aChildInheritsTheSiteAndMayRetargetOrClearIt() throws Exception {
        QuestAsset parent = decodeRoot("""
                { "Npc": { "ViewId": "guide" }, "TurnInAt": true,
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "Stone" } } }
                """, "base");

        QuestTurnInSite inherited = decode("""
                { "Objectives": { "a": { "Amount": 20 } } }
                """, "child", "base", parent).toDefinition(null).turnInAt();
        assertNotNull(inherited, "a leaf that did not inherit would silently unbind every child");
        assertEquals("guide", inherited.id());

        QuestTurnInSite retargeted = decode("""
                { "TurnInAt": "quartermaster" }
                """, "child", "base", parent).toDefinition(null).turnInAt();
        assertNotNull(retargeted);
        assertEquals("quartermaster", retargeted.id());

        assertNull(decode("""
                { "TurnInAt": "" }
                """, "child", "base", parent).toDefinition(null).turnInAt(),
                "an empty string is how one child opts back out of an inherited place");
        assertNull(decode("""
                { "TurnInAt": false }
                """, "child", "base", parent).toDefinition(null).turnInAt(),
                "and false is the one-character form of the same edit");
    }
}
