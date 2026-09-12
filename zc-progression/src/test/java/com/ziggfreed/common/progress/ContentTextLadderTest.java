package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.asset.QuestDefinition;

/**
 * The ONE naming ladder every shared surface reads a title through, pinned rung by rung: an
 * explicit key that resolves, a convention key that resolves, the authored name, the first step's
 * own line, and last the explicit key exactly as written.
 *
 * <p>The rung that matters most is the fourth. A bounty, and any quest whose author wrote neither
 * a key nor a name, used to paint its convention key literally on every shared surface at once (the
 * character's offer list, the board, the book, a command's listing) while the mod that folded it
 * named the same quest by its first step everywhere else. There is no consumer-side ladder any
 * more, so this is where the rule lives and where it must not regress.
 *
 * <p>Every catalogue here is a fixture handed to {@link LangCatalog}; no production lang file is
 * read.
 */
class ContentTextLadderTest {

    private static final String NS = "ziggfreedcommon.progress.";

    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    /** A content group with every rung filled, over a catalogue that resolves only what the test says. */
    private static ContentText.Builder everyRung() {
        return ContentText.builder()
                .titleKey("fixture.explicit.title")
                .titleConventionKey("quest.ladder.title")
                .displayName("An Authored Name")
                .objectiveLine("first", () -> Msg.raw("Mine 10 Iron Ore"))
                .objectiveLine("second", () -> Msg.raw("Report to the guide"));
    }

    @Test
    void anExplicitKeyThatResolvesBeatsEverything() {
        LangCatalog.overrideForTests(Map.of(
                "fixturemod.fixture.explicit.title", "Explicit",
                "fixturemod.quest.ladder.title", "Convention"));

        assertEquals("fixturemod.fixture.explicit.title", everyRung().build().title().getMessageId());
    }

    @Test
    void aConventionKeyThatResolvesBeatsTheAuthoredName() {
        LangCatalog.overrideForTests(Map.of("fixturemod.quest.ladder.title", "Convention"));

        assertEquals("fixturemod.quest.ladder.title", everyRung().build().title().getMessageId(),
                "a pack ships a localized title through .lang alone, with no per-file edit");
    }

    @Test
    void theAuthoredNameBeatsTheFirstStep() {
        LangCatalog.overrideForTests(Map.of());

        assertEquals("An Authored Name", everyRung().build().title().getRawText(),
                "an author who shipped a name but no lang key gets their own name, never an"
                        + " inferred sentence");
    }

    /**
     * The regression under test: nothing resolves and nothing was authored, and the content still
     * has a name a player can act on - its first step's line - rather than the convention key
     * painted literally.
     */
    @Test
    void aKeyLessNameLessContentIsTitledByItsFirstStep() {
        LangCatalog.overrideForTests(Map.of());
        ContentText text = ContentText.builder()
                .titleConventionKey("quest.ladder.title")
                .objectiveLine("first", () -> Msg.raw("Mine 10 Iron Ore"))
                .objectiveLine("second", () -> Msg.raw("Report to the guide"))
                .build();

        Message title = text.title();
        assertNotNull(title);
        assertEquals("Mine 10 Iron Ore", title.getRawText(),
                "the FIRST step stamped names it, the same words its step list opens with");
        assertEquals("Mine 10 Iron Ore", text.titleOr("ladder").getRawText());
    }

    /** A first step whose composer has nothing to say yet falls to that step's authored key. */
    @Test
    void aFirstStepWithNoLineYetStillNamesByItsOwnKey() {
        LangCatalog.overrideForTests(Map.of());
        ContentText text = ContentText.builder()
                .objectiveKey("first", "objective.text.mine.iron")
                .objectiveLine("first", () -> null)
                .build();

        assertEquals("objective.text.mine.iron", text.title().getMessageId());
    }

    /** With no step either, the explicit key stands as written: traceable, where a blank is not. */
    @Test
    void withNoStepTheExplicitKeyStandsAsWritten() {
        LangCatalog.overrideForTests(Map.of());
        ContentText text = ContentText.builder()
                .titleKey("fixture.explicit.title")
                .titleConventionKey("quest.ladder.title")
                .build();

        assertEquals("fixture.explicit.title", text.title().getMessageId());
    }

    /**
     * A convention key nobody wrote is not painted: a fold derives it from the id, so its absence
     * is the ordinary state of content with no words, and the reader's own fallback (the id, the
     * shared placeholder line) takes over instead of a raw key.
     */
    @Test
    void anUnfilledConventionKeyAloneNamesNothing() {
        LangCatalog.overrideForTests(Map.of());
        ContentText text = ContentText.builder().titleConventionKey("quest.ladder.title").build();

        assertNull(text.title());
        assertEquals("ladder", text.titleOr("ladder").getRawText());
    }

    /** The flavor ladder: the description, then the explicit key as written, and never a step. */
    @Test
    void flavorEndsAtTheDescriptionThenTheExplicitKeyAsWritten() {
        LangCatalog.overrideForTests(Map.of());

        ContentText described = ContentText.builder()
                .flavorKey("fixture.explicit.flavor")
                .flavorConventionKey("quest.ladder.flavor")
                .description("A line under the title.")
                .objectiveLine("first", () -> Msg.raw("Mine 10 Iron Ore"))
                .build();
        assertEquals("A line under the title.", described.flavor().getRawText());

        ContentText explicitOnly = ContentText.builder()
                .flavorKey("fixture.explicit.flavor")
                .flavorConventionKey("quest.ladder.flavor")
                .objectiveLine("first", () -> Msg.raw("Mine 10 Iron Ore"))
                .build();
        assertEquals("fixture.explicit.flavor", explicitOnly.flavor().getMessageId(),
                "a key the author wrote is traceable, so it stands");

        ContentText conventionOnly = ContentText.builder()
                .flavorConventionKey("quest.ladder.flavor")
                .objectiveLine("first", () -> Msg.raw("Mine 10 Iron Ore"))
                .build();
        assertNull(conventionOnly.flavor(),
                "most content has no line under its title, and that is what an unfilled"
                        + " convention key means: nothing is painted, and no step stands in");
    }

    /**
     * Through the real fold: a shared-schema quest with no {@code Text} at all (the shape a bounty
     * contract arrives in) is titled by its first step IN AUTHORED ORDER, composed by the library's
     * own sentence family.
     */
    @Test
    void aFoldedQuestWithNoWordsIsTitledByItsFirstStepInAuthoredOrder() {
        LangCatalog.overrideForTests(Map.of(
                NS + "objective.pickup_item", "Collect {0} {1}",
                NS + "objective.kill_entity", "Defeat {0} {1}"));
        Quest quest = Quest.builder("contract_with_no_words")
                .objective(ObjectiveDef.builder("gather", "PICKUP_ITEM")
                        .target("Ore").amount(12).build())
                .objective(ObjectiveDef.builder("slay", "KILL_ENTITY")
                        .target("Trork").amount(3).build())
                .build();
        QuestDefinition folded = new QuestDefinition("contract_with_no_words", quest, null, null,
                null, List.of(), List.of(), Map.of(), null, 0, List.of(), null, null, null, null,
                GateSpec.OPEN, List.of(), Map.of(), List.of(), null, Map.of());

        Message title = folded.quest().text().title();
        assertNotNull(title, "a contract with no Text block is still called something");
        assertEquals(NS + "objective.pickup_item", title.getMessageId(),
                "the first authored step, not the second, and not a raw key");
    }
}
