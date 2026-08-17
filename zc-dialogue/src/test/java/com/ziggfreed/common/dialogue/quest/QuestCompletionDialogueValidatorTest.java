package com.ziggfreed.common.dialogue.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The audit for a completion conversation nothing can open.
 *
 * <p>The point of the check is that this failure is INVISIBLE at runtime: the quest finishes, the
 * rewards land, and the giver simply says nothing, which reads as a missing beat rather than a
 * mistyped id. The point of it living beside the routing is that it asks the same host table the
 * hand-off asks, so the audit and the game can never disagree.
 */
class QuestCompletionDialogueValidatorTest {

    /** A host that knows one conversation and never paints anything. */
    private static QuestDialogueHost knowing(@Nonnull String knownId) {
        return new QuestDialogueHost() {

            @Override
            public boolean knows(@Nonnull String dialogueId) {
                return knownId.equals(dialogueId);
            }

            @Override
            public boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
                    @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
                return false;
            }
        };
    }

    @BeforeEach
    @AfterEach
    void reset() {
        QuestDialogueHosts.clear();
    }

    @Test
    void aConversationNoHostKnowsIsOneWarningNamingTheQuest() {
        QuestDialogueHosts.register("mymod", "A", knowing("guide_thanks"));

        Finding finding = QuestCompletionDialogueValidator.check("guide_thnaks", "craft_starter_tools");

        assertNotNull(finding);
        assertEquals(Severity.WARNING, finding.severity(),
                "whoever owns the conversation may register later, or not be installed at all");
        assertEquals(QuestCompletionDialogueValidator.CODE, finding.code());
        assertEquals("craft_starter_tools", finding.sourceId(), "the quest is the file an author opens");
        assertEquals(QuestCompletionDialogueValidator.DOMAIN, finding.domain());
        assertTrue(finding.message().contains("guide_thnaks"), "the message names the id that is wrong");
    }

    @Test
    void aKnownConversationReportsNothing() {
        QuestDialogueHosts.register("mymod", "A", knowing("guide_thanks"));

        assertNull(QuestCompletionDialogueValidator.check("guide_thanks", "craft_starter_tools"));
        assertNull(QuestCompletionDialogueValidator.check("  guide_thanks  ", "craft_starter_tools"),
                "the same trimming the routing does, so the audit cannot report what the game will play");
    }

    @Test
    void aQuestNamingNoConversationReportsNothing() {
        assertNull(QuestCompletionDialogueValidator.check(null, "gather_the_basics"));
        assertNull(QuestCompletionDialogueValidator.check("   ", "gather_the_basics"));
    }

    @Test
    void aServerWithNoConversationUiAtAllReportsEveryNamedConversation() {
        assertNotNull(QuestCompletionDialogueValidator.check("guide_thanks", "craft_starter_tools"),
                "nothing registered means nothing can open it, which is exactly what the finding says");
    }
}
